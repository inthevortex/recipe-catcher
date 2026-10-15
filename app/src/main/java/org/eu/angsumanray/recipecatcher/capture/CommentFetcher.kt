package org.eu.angsumanray.recipecatcher.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
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
        private const val SETTLE_MAX_MILLIS = 12_000L
        private const val POLL_INTERVAL_MILLIS = 500L
        private const val MAX_COMMENT_CHARS = 5_000

        private const val CSS_FIX_SCRIPT = """
            (function() {
              var style = document.getElementById('rc-wv-fix');
              if (!style) {
                style = document.createElement('style');
                style.id = 'rc-wv-fix';
                style.textContent = 'html, body, #mount_0_0_0, div[id^="mount_"] { min-height: 100vh !important; height: auto !important; display: flex !important; flex-direction: column !important; }';
                var target = document.head || document.documentElement;
                if (target) {
                  target.appendChild(style);
                }
              }
            })()
        """

        /** The canonical post URL (preserving comment ID if present) for the first Instagram post link in [sharedText], if any. */
        fun postUrlFrom(sharedText: String): String? {
            val url = UrlNormalizer.extractUrls(sharedText).firstOrNull { UrlNormalizer.extractInstagramShortcode(it) != null }
                ?: sharedText.trim().takeIf { UrlNormalizer.extractInstagramShortcode(it) != null }
                ?: return null
            val shortcode = UrlNormalizer.extractInstagramShortcode(url) ?: return null
            val commentId = UrlNormalizer.extractInstagramCommentId(url)
            return UrlNormalizer.canonicalizeInstagramUrl(shortcode, commentId)
        }

        fun commentIdFrom(sharedText: String): String? {
            val url = UrlNormalizer.extractUrls(sharedText).firstOrNull { UrlNormalizer.extractInstagramShortcode(it) != null }
                ?: sharedText.trim().takeIf { UrlNormalizer.extractInstagramShortcode(it) != null }
                ?: return null
            return UrlNormalizer.extractInstagramCommentId(url)
        }
    }

    /**
     * Loads the Instagram post linked in [sharedText] in an off-screen WebView with the saved login
     * cookies, waits for page load plus up to 12 s, and reads the first comment.
     *
     * Call it only from a foreground capture, never from a worker or the bulk import. It returns
     * null on any failure, including a timeout, a changed DOM or a crashed renderer.
     */
    suspend fun fetchTopComment(sharedText: String, knownCaption: String? = null): String? {
        if (!settingsStore.instagramLoggedInFlow.first() || !settingsStore.fetchTopCommentFlow.first()) {
            Log.d(TAG, "Instagram login or fetchTopComment disabled")
            return null
        }
        val url = postUrlFrom(sharedText) ?: return null
        val targetCommentId = commentIdFrom(sharedText)
        Log.d(TAG, "Fetching comment for url=$url, targetCommentId=$targetCommentId")

        return try {
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MILLIS + SETTLE_MAX_MILLIS) {
                    fetchViaWebView(url, targetCommentId, knownCaption)
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
    private suspend fun fetchViaWebView(
        url: String,
        targetCommentId: String? = null,
        knownCaption: String? = null
    ): String? = suspendCancellableCoroutine { cont ->
        val webView = WebView(context)
        webView.layout(0, 0, 1080, 1920)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = false
        settings.javaScriptCanOpenWindowsAutomatically = true

        val defaultUa = settings.userAgentString
        settings.userAgentString = defaultUa
            .replace("; wv", "")
            .replace("Version/4.0 ", "")

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
            try {
                webView.stopLoading()
                webView.destroy()
            } catch (_: Exception) {}
            if (cont.isActive) cont.resume(result)
        }

        fun poll() {
            if (finished) return
            val js = InstaSelectors.buildExtractionJs(targetCommentId, knownCaption)
            webView.evaluateJavascript(js) { raw ->
                val comment = decodeJsString(raw)
                if (comment.isNotBlank()) {
                    Log.d(TAG, "Found comment: ${comment.take(60)}...")
                    finish(comment)
                } else if (SystemClock.uptimeMillis() >= settleDeadline) {
                    Log.d(TAG, "Settle deadline reached without comment")
                    finish(null)
                } else {
                    handler.postDelayed({ poll() }, POLL_INTERVAL_MILLIS)
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view?.evaluateJavascript(CSS_FIX_SCRIPT, null)
            }

            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                view?.evaluateJavascript(CSS_FIX_SCRIPT, null)
                if (finished || settleDeadline != 0L) return
                settleDeadline = SystemClock.uptimeMillis() + SETTLE_MAX_MILLIS
                poll()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    Log.w(TAG, "Main frame error loading $url: ${error?.description}")
                    finish(null)
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Log.w(TAG, "WebView renderer gone while reading comments")
                finish(null)
                return true
            }
        }

        cont.invokeOnCancellation {
            handler.post { finish(null) }
        }

        Log.d(TAG, "Loading URL in offscreen WebView: $url")
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
