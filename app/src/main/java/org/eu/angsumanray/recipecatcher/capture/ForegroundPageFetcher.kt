package org.eu.angsumanray.recipecatcher.capture

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.core.web.WebRecipeData
import org.eu.angsumanray.recipecatcher.core.web.WebRecipeFetcher
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class ForegroundPageFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webRecipeFetcher: WebRecipeFetcher,
    private val imageStorageManager: ImageStorageManager,
    private val database: RecipeDatabase
) {

    /**
     * Attempts to fetch a blocked web page using an off-screen WebView on the main thread,
     * allowing JavaScript and Cloudflare challenges to settle.
     */
    suspend fun retryInWebView(recipeId: Long): Result<WebRecipeData> = withContext(Dispatchers.Main) {
        val recipe = database.recipeDao().getById(recipeId)
            ?: return@withContext Result.failure(IllegalArgumentException("Recipe $recipeId not found"))

        val url = recipe.sourceUrl ?: recipe.sourceKey
        val html = withTimeoutOrNull(20_000L) {
            fetchHtmlViaWebView(url)
        } ?: return@withContext Result.failure(IllegalStateException("WebView fetch timed out"))

        val webData = webRecipeFetcher.parseHtml(html, url)
            ?: return@withContext Result.failure(IllegalStateException("Could not parse recipe from page"))

        val imagePath = webData.imageUrl?.let {
            imageStorageManager.downloadAndSave(recipeId, it)
        }

        val updated = recipe.copy(
            title = webData.title ?: recipe.title,
            captionRaw = webData.sourceText,
            authorHandle = webData.author ?: recipe.authorHandle,
            imagePath = imagePath ?: recipe.imagePath,
            status = RecipeEntity.STATUS_PENDING_EXTRACT,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )
        database.recipeDao().update(updated)

        CaptureWorker.enqueue(context, recipeId)
        Result.success(webData)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchHtmlViaWebView(url: String): String = suspendCancellableCoroutine { cont ->
        val webView = WebView(context)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = WebRecipeFetcher.DESKTOP_USER_AGENT
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true

        val handler = Handler(Looper.getMainLooper())
        var finished = false

        fun completeWithHtml() {
            if (finished) return
            finished = true
            webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { raw ->
                val html = if (!raw.isNullOrBlank() && raw != "null") {
                    try {
                        Json.decodeFromString<String>(raw)
                    } catch (_: Exception) {
                        raw.removeSurrounding("\"")
                            .replace("\\\"", "\"")
                            .replace("\\n", "\n")
                            .replace("\\t", "\t")
                    }
                } else {
                    ""
                }
                webView.destroy()
                if (cont.isActive) cont.resume(html)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                super.onPageFinished(view, finishedUrl)
                // Wait 2 seconds after page finished to allow JS challenge to resolve
                handler.postDelayed({ completeWithHtml() }, 2000L)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    handler.postDelayed({ completeWithHtml() }, 1000L)
                }
            }
        }

        cont.invokeOnCancellation {
            handler.removeCallbacksAndMessages(null)
            webView.destroy()
        }

        webView.loadUrl(url)
    }
}
