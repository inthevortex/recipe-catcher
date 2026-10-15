package org.eu.angsumanray.recipecatcher.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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
        /** After the page loads, the comments endpoint and rendered comments get up to this long. */
        private const val SETTLE_MAX_MILLIS = 25_000L
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

        const val LINK_NEEDS_LOGIN_MESSAGE =
            "Log in to Instagram in Settings so the app can read comments, or paste the comment text itself."
        const val LINK_NO_COMMENT_MESSAGE =
            "Couldn't find the recipe comment from that link. Open the post in the app browser and select the recipe, or paste the comment text itself."

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

    suspend fun isInstagramLoggedIn(): Boolean = settingsStore.instagramLoggedInFlow.first()

    /** Why reading comments from a pasted link failed, worded for the user. */
    suspend fun linkFailureMessage(): String =
        if (isInstagramLoggedIn()) LINK_NO_COMMENT_MESSAGE else LINK_NEEDS_LOGIN_MESSAGE

    /**
     * Loads the Instagram post linked in [sharedText] in an off-screen WebView with the saved login
     * cookies and reads the recipe comment: the linked comment when [sharedText] is a comment link,
     * otherwise the author's or another recipe-like comment.
     *
     * [userRequested] skips the "fetch top comment" setting, for when the user asked for this
     * post's comments directly. Call it only from a foreground capture, never from a worker or the
     * bulk import. It returns null on any failure, including a timeout, a changed DOM or a crashed
     * renderer.
     */
    suspend fun fetchTopComment(
        sharedText: String,
        knownCaption: String? = null,
        userRequested: Boolean = false
    ): String? {
        if (!isInstagramLoggedIn() || (!userRequested && !settingsStore.fetchTopCommentFlow.first())) {
            Log.d(TAG, "Instagram login or fetchTopComment disabled")
            return null
        }
        val url = postUrlFrom(sharedText) ?: return null
        val shortcode = UrlNormalizer.extractInstagramShortcode(url)
        val targetCommentId = commentIdFrom(sharedText)
        Log.d(TAG, "Fetching comment for url=$url, targetCommentId=$targetCommentId")

        return try {
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MILLIS + SETTLE_MAX_MILLIS) {
                    fetchViaWebView(url, shortcode, targetCommentId, knownCaption)
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
        shortcode: String?,
        targetCommentId: String?,
        knownCaption: String?
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

        var finished = false
        var reader: InstagramCommentReader? = null

        fun finish(result: String?) {
            if (finished) return
            finished = true
            reader?.cancel()
            try {
                webView.stopLoading()
                webView.destroy()
            } catch (_: Exception) {}
            if (cont.isActive) cont.resume(result)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view?.evaluateJavascript(CSS_FIX_SCRIPT, null)
            }

            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                view?.evaluateJavascript(CSS_FIX_SCRIPT, null)
                if (finished || reader != null) return
                reader = InstagramCommentReader(webView, shortcode, targetCommentId, knownCaption, SETTLE_MAX_MILLIS) { comment ->
                    if (comment != null) Log.d(TAG, "Found comment (${comment.length} chars)")
                    finish(comment)
                }.also { it.start() }
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
            Handler(Looper.getMainLooper()).post { finish(null) }
        }

        Log.d(TAG, "Loading URL in offscreen WebView: $url")
        webView.loadUrl(url)
    }
}
