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

    companion object {
        private const val TIMEOUT_MILLIS = 20_000L
        private const val SETTLE_MILLIS = 2_000L
    }

    /**
     * Loads a blocked page in an off-screen WebView on the main thread, so JavaScript and
     * Cloudflare challenges can run, then hands the page to the capture worker for extraction.
     */
    suspend fun retryInWebView(recipeId: Long): Result<WebRecipeData> {
        val recipe = database.recipeDao().getById(recipeId)
            ?: return Result.failure(IllegalArgumentException("Recipe $recipeId not found"))

        val url = recipe.sourceUrl ?: recipe.sourceKey
        val html = withContext(Dispatchers.Main) {
            withTimeoutOrNull(TIMEOUT_MILLIS) { fetchHtmlViaWebView(url) }
        } ?: return Result.failure(IllegalStateException("The page did not finish loading"))

        val webData = withContext(Dispatchers.Default) { webRecipeFetcher.parseHtml(html, url) }
            ?: return Result.failure(IllegalStateException("Could not parse recipe from page"))

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
        return Result.success(webData)
    }

    /**
     * Resumes with the page HTML once a load settles on something other than a challenge
     * interstitial. A challenge that resolves navigates again, which triggers another check.
     */
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

        fun finish(html: String) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            webView.destroy()
            if (cont.isActive) cont.resume(html)
        }

        fun checkPage() {
            if (finished) return
            webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { raw ->
                val html = decodeJsString(raw)
                if (html.isNotBlank() && !WebRecipeFetcher.isChallengePage(html)) {
                    finish(html)
                } else if (!finished) {
                    handler.postDelayed({ checkPage() }, SETTLE_MILLIS)
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                super.onPageFinished(view, finishedUrl)
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ checkPage() }, SETTLE_MILLIS)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    finish("")
                }
            }
        }

        cont.invokeOnCancellation {
            handler.post {
                handler.removeCallbacksAndMessages(null)
                if (!finished) {
                    finished = true
                    webView.destroy()
                }
            }
        }

        webView.loadUrl(url)
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            Json.decodeFromString<String>(raw)
        } catch (_: Exception) {
            ""
        }
    }
}
