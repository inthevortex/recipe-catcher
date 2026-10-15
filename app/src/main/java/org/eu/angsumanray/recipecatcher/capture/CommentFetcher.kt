package org.eu.angsumanray.recipecatcher.capture

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.core.capture.UrlNormalizer
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
        private const val PAGE_LOAD_TIMEOUT_MILLIS = 15_000L
        /** After the page loads, comments get up to this long to render. */
        private const val SETTLE_MAX_MILLIS = 5_000L
        private const val POLL_INTERVAL_MILLIS = 500L
        private const val MAX_COMMENT_CHARS = 5_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** The canonical post URL for the first Instagram post link in [sharedText], if any. */
        fun postUrlFrom(sharedText: String): String? =
            UrlNormalizer.extractUrls(sharedText)
                .firstNotNullOfOrNull { UrlNormalizer.extractInstagramShortcode(it) }
                ?.let { UrlNormalizer.canonicalizeInstagramUrl(it) }
    }

    /**
     * Loads the Instagram post linked in [sharedText] in an off-screen WebView with the saved login
     * cookies, waits for page load plus up to 5 s, and reads the first comment.
     *
     * Call it only from a foreground capture, never from a worker or the bulk import. It returns
     * null on any failure, including a timeout, a changed DOM or a crashed renderer.
     */
    suspend fun fetchTopComment(sharedText: String): String? {
        if (!settingsStore.instagramLoggedInFlow.first() || !settingsStore.fetchTopCommentFlow.first()) {
            return null
        }
        val url = postUrlFrom(sharedText) ?: return null

        return try {
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MILLIS + SETTLE_MAX_MILLIS) {
                    fetchViaWebView(url)
                }
            }?.take(MAX_COMMENT_CHARS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch top comment for $url", e)
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchViaWebView(url: String): String? = suspendCancellableCoroutine { cont ->
        val webView = WebView(context)
        webView.layout(0, 0, 1080, 1920)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString = USER_AGENT

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var settleDeadline = 0L

        fun finish(result: String?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            webView.stopLoading()
            webView.destroy()
            if (cont.isActive) cont.resume(result)
        }

        fun poll() {
            if (finished) return
            webView.evaluateJavascript(InstaSelectors.buildExtractionJs()) { raw ->
                val comment = decodeJsString(raw)
                when {
                    comment.isNotBlank() -> finish(comment)
                    SystemClock.uptimeMillis() >= settleDeadline -> finish(null)
                    else -> handler.postDelayed({ poll() }, POLL_INTERVAL_MILLIS)
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                // Redirects can report several loads; the settle window starts at the first.
                if (finished || settleDeadline != 0L) return
                settleDeadline = SystemClock.uptimeMillis() + SETTLE_MAX_MILLIS
                poll()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) finish(null)
            }

            // Without this, a crashed renderer takes the whole app down with it.
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Log.w(TAG, "WebView renderer gone while reading comments")
                finish(null)
                return true
            }
        }

        cont.invokeOnCancellation {
            handler.post { finish(null) }
        }

        webView.loadUrl(url)
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            Json.decodeFromString<String>(raw)
        } catch (_: Exception) {
            raw.removeSurrounding("\"")
        }.trim()
    }
}
