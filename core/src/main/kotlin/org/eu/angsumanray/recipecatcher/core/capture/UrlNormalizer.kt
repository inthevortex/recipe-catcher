package org.eu.angsumanray.recipecatcher.core.capture

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern

object UrlNormalizer {

    private val URL_IN_TEXT_REGEX = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE)

    private val INSTAGRAM_REGEX = Pattern.compile(
        "^https?://(?:www\\.)?(?:instagram\\.com|instagr\\.am)/(?:p|reel|reels|tv)/([a-zA-Z0-9_-]+)/?",
        Pattern.CASE_INSENSITIVE
    )

    private val YOUTUBE_SHORTS_OR_EMBED_REGEX = Pattern.compile(
        "^https?://(?:www\\.|m\\.)?youtube\\.com/(?:shorts|embed|v)/([a-zA-Z0-9_-]{6,})/?",
        Pattern.CASE_INSENSITIVE
    )

    private val YOUTUBE_SHORT_DOMAIN_REGEX = Pattern.compile(
        "^https?://youtu\\.be/([a-zA-Z0-9_-]{6,})/?",
        Pattern.CASE_INSENSITIVE
    )

    private val YOUTUBE_WATCH_REGEX = Pattern.compile(
        "^https?://(?:www\\.|m\\.)?youtube\\.com/watch",
        Pattern.CASE_INSENSITIVE
    )

    private val TRACKING_PARAMS = setOf(
        "igsh",
        "fbclid",
        "gclid",
        "si",
        "msclkid",
        "mc_cid",
        "mc_eid",
        "_ga",
        "_gl",
        "ref",
        "feature"
    )

    /**
     * Extracts the first URL found in any arbitrary text (e.g. shared post text from apps).
     * Sentence punctuation directly after the URL is not treated as part of it.
     */
    fun extractUrl(text: String): String? = extractUrls(text).firstOrNull()

    /** All URLs in [text], in order, with trailing sentence punctuation removed. */
    fun extractUrls(text: String): List<String> {
        val matcher = URL_IN_TEXT_REGEX.matcher(text)
        val urls = mutableListOf<String>()
        while (matcher.find()) {
            urls += matcher.group().trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
        }
        return urls
    }

    /**
     * Checks if a query parameter key is a tracking parameter that should be stripped.
     */
    fun isTrackingParam(key: String): Boolean {
        val lower = key.lowercase(Locale.ROOT)
        if (lower.startsWith("utm_")) return true
        return lower in TRACKING_PARAMS
    }

    /**
     * Removes tracking parameters (igsh, utm_*, fbclid, etc.) and the fragment from any URL.
     * Works on the raw string so percent-encoded values pass through unchanged.
     */
    fun stripTrackingParams(url: String): String {
        val withoutFragment = url.trim().substringBefore('#')
        val base = withoutFragment.substringBefore('?')
        val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        val kept = query.split('&').filter { pair ->
            pair.isNotBlank() && !isTrackingParam(pair.substringBefore('=').trim())
        }
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }

    /**
     * Extracts an Instagram shortcode from a post/reel/tv URL.
     * Returns null if the URL is not an Instagram post.
     */
    fun extractInstagramShortcode(url: String): String? {
        val matcher = INSTAGRAM_REGEX.matcher(url.trim())
        return if (matcher.find()) {
            matcher.group(1)
        } else null
    }

    /**
     * Canonicalizes an Instagram URL to https://www.instagram.com/p/<code>/
     */
    fun canonicalizeInstagramUrl(shortcode: String): String {
        return "https://www.instagram.com/p/$shortcode/"
    }

    /**
     * Extracts a YouTube video ID from watch, shorts, embed, or youtu.be URLs.
     */
    fun extractYouTubeVideoId(url: String): String? {
        val trimmed = url.trim()

        val shortDomainMatcher = YOUTUBE_SHORT_DOMAIN_REGEX.matcher(trimmed)
        if (shortDomainMatcher.find()) {
            return shortDomainMatcher.group(1)
        }

        val shortsMatcher = YOUTUBE_SHORTS_OR_EMBED_REGEX.matcher(trimmed)
        if (shortsMatcher.find()) {
            return shortsMatcher.group(1)
        }

        if (YOUTUBE_WATCH_REGEX.matcher(trimmed).find()) {
            try {
                val uri = URI(trimmed)
                val query = uri.rawQuery ?: ""
                for (pair in query.split('&')) {
                    val parts = pair.split('=', limit = 2)
                    if (parts[0].equals("v", ignoreCase = true) && parts.size == 2) {
                        return URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
                    }
                }
            } catch (_: Exception) {
                // Regex fallback for watch?v=...
                val vMatcher = Pattern.compile("[?&]v=([a-zA-Z0-9_-]+)").matcher(trimmed)
                if (vMatcher.find()) {
                    return vMatcher.group(1)
                }
            }
        }

        return null
    }

    /**
     * Canonicalizes a YouTube video to https://www.youtube.com/watch?v=<id>
     */
    fun canonicalizeYouTubeUrl(videoId: String): String {
        return "https://www.youtube.com/watch?v=$videoId"
    }

    /**
     * Normalizes a web URL: trims, strips tracking params, lowercases scheme/host, normalizes path.
     */
    fun normalizeWebUrl(url: String): String {
        val cleaned = stripTrackingParams(url)
        return try {
            val uri = URI(cleaned)
            val scheme = (uri.scheme ?: "https").lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return cleaned
            val port = if (uri.port == 80 && scheme == "http" || uri.port == 443 && scheme == "https") -1 else uri.port
            val path = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath
            // Rebuilt from raw components: URI's multi-argument constructors re-quote '%'.
            buildString {
                append(scheme).append("://")
                uri.rawUserInfo?.let { append(it).append('@') }
                append(host)
                if (port != -1) append(':').append(port)
                append(path)
                uri.rawQuery?.let { append('?').append(it) }
            }
        } catch (_: Exception) {
            cleaned
        }
    }
}
