package org.eu.angsumanray.recipecatcher.capture

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.eu.angsumanray.recipecatcher.core.insta.InstagramComments

/**
 * Reads the recipe comment from an Instagram page already loaded in [webView]: first through
 * Instagram's comments endpoint, then from the rendered comment rows. Must be used on the main
 * thread; [onResult] is called exactly once, unless [cancel] is called first.
 */
class InstagramCommentReader(
    private val webView: WebView,
    private val shortcode: String?,
    private val targetCommentId: String?,
    private val knownCaption: String?,
    private val timeoutMillis: Long,
    private val onResult: (String?) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var done = false
    private var deadline = 0L
    private var apiDeadline = 0L
    private var apiPending = false

    fun start() {
        val now = SystemClock.uptimeMillis()
        deadline = now + timeoutMillis
        val mediaId = shortcode?.let(InstagramComments::mediaIdFromShortcode)
        if (mediaId != null) {
            apiPending = true
            apiDeadline = now + minOf(API_TIMEOUT_MILLIS, timeoutMillis)
            webView.evaluateJavascript(InstaSelectors.buildCommentsApiJs(mediaId, targetCommentId), null)
        }
        poll()
    }

    fun cancel() {
        done = true
        handler.removeCallbacksAndMessages(null)
    }

    private fun poll() {
        if (done) return
        if (apiPending) pollApi() else pollDom()
    }

    private fun pollApi() {
        webView.evaluateJavascript(InstaSelectors.READ_COMMENTS_API_JS) { raw ->
            if (done) return@evaluateJavascript
            val state = parseObject(decodeJsString(raw))
            when (state?.string("state")) {
                "done" -> {
                    apiPending = false
                    val body = state.string("body").orEmpty()
                    val comments = InstagramComments.parse(body)
                    Log.d(
                        TAG,
                        "Comments endpoint: ${comments.size} comments, target " +
                            (if (targetCommentId == null) "none" else if (comments.any { it.id == targetCommentId }) "found" else "missing") +
                            " [${state.string("notes").orEmpty()}]"
                    )
                    val picked = InstagramComments.pickRecipeComment(body, targetCommentId, knownCaption)
                    if (picked != null) {
                        Log.d(TAG, "Comment read from comments endpoint")
                        finish(picked)
                        return@evaluateJavascript
                    }
                    Log.d(TAG, "Comments endpoint had no recipe-like comment; reading the page")
                }
                "error" -> {
                    apiPending = false
                    Log.d(TAG, "Comments endpoint failed: ${state.string("error")}; reading the page")
                }
                else -> if (SystemClock.uptimeMillis() >= apiDeadline) {
                    apiPending = false
                    Log.d(TAG, "Comments endpoint timed out; reading the page")
                }
            }
            scheduleNext()
        }
    }

    private fun pollDom() {
        val js = InstaSelectors.buildExtractionJs(targetCommentId, knownCaption)
        webView.evaluateJavascript(js) { raw ->
            if (done) return@evaluateJavascript
            val result = parseObject(decodeJsString(raw))
            val direct = result?.string("target") ?: result?.string("pinned")
            if (direct != null) {
                finish(direct)
                return@evaluateJavascript
            }
            val texts = try {
                result?.get("texts")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            InstagramComments.mostRecipeLike(texts)
                ?.takeIf { InstagramComments.recipeScore(it) >= MIN_PAGE_RECIPE_SCORE }
                ?.let { best ->
                    finish(best)
                    return@evaluateJavascript
                }
            scheduleNext()
        }
    }

    private fun scheduleNext() {
        if (done) return
        if (SystemClock.uptimeMillis() >= deadline) {
            Log.d(TAG, "Deadline reached without a recipe-like comment")
            finish(null)
        } else {
            handler.postDelayed({ poll() }, POLL_INTERVAL_MILLIS)
        }
    }

    private fun finish(result: String?) {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        onResult(result)
    }

    companion object {
        private const val TAG = "InstagramCommentReader"
        private const val POLL_INTERVAL_MILLIS = 500L
        private const val API_TIMEOUT_MILLIS = 20_000L

        /** Rendered comments below this score are requests or chatter, not the recipe. */
        private const val MIN_PAGE_RECIPE_SCORE = 2

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Unwraps the JSON-encoded string `evaluateJavascript` hands back; blank for `null`. */
        fun decodeJsString(raw: String?): String {
            if (raw.isNullOrBlank() || raw == "null") return ""
            return try {
                Json.decodeFromString<String>(raw)
            } catch (_: Exception) {
                raw.removeSurrounding("\"")
            }.trim()
        }

        private fun parseObject(text: String): JsonObject? = try {
            if (text.isBlank()) null else json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
