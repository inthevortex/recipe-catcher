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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.eu.angsumanray.recipecatcher.core.capture.UrlNormalizer
import org.eu.angsumanray.recipecatcher.core.insta.InstagramComments
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
        /** After the page loads, the media info lookup gets up to this long. */
        private const val MEDIA_INFO_MAX_MILLIS = 10_000L
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
     * post's comments directly. Each call loads a page in a WebView, so it is not for the bulk
     * import. It returns null on any failure, including a timeout, a changed DOM or a crashed
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
                    readLoadedPage(url) { webView, done ->
                        val reader = InstagramCommentReader(webView, shortcode, targetCommentId, knownCaption, SETTLE_MAX_MILLIS) { comment ->
                            if (comment != null) Log.d(TAG, "Found comment (${comment.length} chars)")
                            done(comment)
                        }
                        reader.start()
                        reader::cancel
                    }
                }
            }?.take(MAX_COMMENT_CHARS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch top comment for $url", e)
            null
        }
    }

    /**
     * The URL of the cover image of the Instagram post at [postUrl], read through the logged-in
     * session, or null when not logged in or on any failure.
     */
    suspend fun fetchCoverImageUrl(postUrl: String): String? {
        if (!isInstagramLoggedIn()) return null
        val shortcode = UrlNormalizer.extractInstagramShortcode(postUrl) ?: return null
        val mediaId = InstagramComments.mediaIdFromShortcode(shortcode) ?: return null
        val url = UrlNormalizer.canonicalizeInstagramUrl(shortcode)
        return try {
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MILLIS + MEDIA_INFO_MAX_MILLIS) {
                    readLoadedPage(url) { webView, done -> readMediaInfo(webView, mediaId, done) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch cover image for $url", e)
            null
        }
    }

    /** Polls the media info lookup started on [webView]; returns a function that stops polling. */
    private fun readMediaInfo(webView: WebView, mediaId: String, done: (String?) -> Unit): () -> Unit {
        val handler = Handler(Looper.getMainLooper())
        val deadline = SystemClock.uptimeMillis() + MEDIA_INFO_MAX_MILLIS
        webView.evaluateJavascript(InstaSelectors.buildMediaInfoJs(mediaId), null)
        fun poll() {
            webView.evaluateJavascript(InstaSelectors.READ_MEDIA_INFO_JS) { raw ->
                val state = try {
                    Json.parseToJsonElement(InstagramCommentReader.decodeJsString(raw)) as? JsonObject
                } catch (_: Exception) {
                    null
                }
                val finished = (state?.get("state") as? JsonPrimitive)?.contentOrNull == "done"
                when {
                    finished -> {
                        (state?.get("error") as? JsonPrimitive)?.contentOrNull?.let { Log.d(TAG, "Media info failed: $it; using og:image") }
                        done((state?.get("url") as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") })
                    }
                    SystemClock.uptimeMillis() >= deadline -> done(null)
                    else -> handler.postDelayed({ poll() }, POLL_INTERVAL_MILLIS)
                }
            }
        }
        poll()
        return { handler.removeCallbacksAndMessages(null) }
    }

    /**
     * Loads [url] in an off-screen WebView with the saved login cookies, then hands it to
     * [startReading] once the page has loaded. [startReading] calls its callback once with the
     * result and returns a function that stops its work early.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun readLoadedPage(
        url: String,
        startReading: (WebView, (String?) -> Unit) -> () -> Unit
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
        var stopReading: (() -> Unit)? = null

        fun finish(result: String?) {
            if (finished) return
            finished = true
            stopReading?.invoke()
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
                if (finished || stopReading != null) return
                stopReading = startReading(webView) { result -> finish(result) }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    Log.w(TAG, "Main frame error loading $url: ${error?.description}")
                    finish(null)
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Log.w(TAG, "WebView renderer gone while reading the page")
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
