package org.eu.angsumanray.recipecatcher.capture

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class CommentFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore
) {

    companion object {
        private const val TAG = "CommentFetcher"
        private const val TIMEOUT_MILLIS = 15_000L
        private const val SETTLE_MILLIS = 3_000L // Up to 3-5s for Instagram dynamic DOM to render
    }

    /**
     * Loads the Instagram post URL in an off-screen WebView with saved cookies,
     * waits for page load plus settle time, then runs evaluateJavascript to read the top comment.
     *
     * Invariants:
     * - Runs ONLY on foreground captures (never in background worker or bulk import).
     * - Returns null on any failure, never throws or crashes capture.
     */
    suspend fun fetchTopComment(url: String): String? {
        val isLoggedIn = settingsStore.instagramLoggedInFlow.first()
        val isFetchEnabled = settingsStore.fetchTopCommentFlow.first()
        if (!isLoggedIn || !isFetchEnabled) {
            return null
        }

        return try {
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(TIMEOUT_MILLIS) {
                    fetchViaWebView(url)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch top comment for $url", e)
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchViaWebView(url: String): String? = suspendCancellableCoroutine { cont ->
        val webView = WebView(context)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val handler = Handler(Looper.getMainLooper())
        var finished = false

        fun finish(result: String?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            webView.destroy()
            if (cont.isActive) cont.resume(result)
        }

        fun evaluateCommentJs() {
            if (finished) return
            webView.evaluateJavascript(InstaSelectors.buildExtractionJs()) { raw ->
                val comment = decodeJsString(raw)
                if (comment.isNotBlank() && comment != "null") {
                    finish(comment)
                } else {
                    finish(null)
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                super.onPageFinished(view, finishedUrl)
                handler.removeCallbacksAndMessages(null)
                // Wait up to SETTLE_MILLIS after page load for comments to mount in DOM
                handler.postDelayed({ evaluateCommentJs() }, SETTLE_MILLIS)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    finish(null)
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
            raw.removeSurrounding("\"")
        }
    }
}
