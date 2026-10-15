package org.eu.angsumanray.recipecatcher.core.youtube

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eu.angsumanray.recipecatcher.core.capture.UrlNormalizer
import org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher
import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

open class YouTubeFetcher(
    private val httpFetcher: HttpFetcher,
    private val log: (String) -> Unit = {}
) {
    companion object {
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        val SOCIAL_DOMAINS = setOf(
            "instagram.com", "instagr.am",
            "youtube.com", "youtu.be",
            "facebook.com", "fb.me", "fb.com",
            "twitter.com", "x.com",
            "tiktok.com",
            "pinterest.com", "pin.it",
            "spotify.com",
            "patreon.com",
            "amazon.com", "amzn.to",
            "discord.gg", "discord.com",
            "linkedin.com",
            "reddit.com",
            "threads.net"
        )

        fun thumbnailUrlForVideoId(videoId: String): String =
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        /**
         * Finds up to [maxLinks] non-social web links in the video description for linked-page fallback.
         */
        fun findCandidateRecipeLinks(description: String, maxLinks: Int = 2): List<String> {
            val urls = UrlNormalizer.extractUrls(description)
            return urls.filter { url ->
                val host = try {
                    URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.")
                } catch (_: Exception) {
                    null
                } ?: return@filter false

                SOCIAL_DOMAINS.none { host == it || host.endsWith(".$it") }
            }.distinct().take(maxLinks)
        }
    }

    private val PLAYER_RESPONSE_ASSIGNMENT = Regex("""ytInitialPlayerResponse["']?\]?\s*=\s*\{""")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    open suspend fun fetch(urlOrId: String): YouTubeData? {
        val videoId = UrlNormalizer.extractYouTubeVideoId(urlOrId)
            ?: urlOrId.trim().takeIf { it.isNotBlank() && !it.contains("/") }
            ?: return null

        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val headers = mapOf(
            "User-Agent" to DESKTOP_USER_AGENT,
            "Accept-Language" to "en",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            // Skips the EU cookie-consent interstitial, which has no player response.
            "Cookie" to "SOCS=CAI"
        )

        val response = try {
            httpFetcher.get(watchUrl, headers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("YouTube fetch request failed for $videoId: ${e.message}")
            return null
        }

        if (!response.isSuccessful) {
            log("YouTube fetch received HTTP ${response.statusCode} for $videoId")
            return null
        }

        return parseHtml(response.body, videoId)
    }

    /**
     * Parses the HTML body of a YouTube watch page.
     */
    fun parseHtml(html: String, videoId: String): YouTubeData? {
        val thumbnailUrl = thumbnailUrlForVideoId(videoId)

        // 1. Try ytInitialPlayerResponse JSON
        val playerJsonString = extractInitialPlayerResponseJson(html)
        if (!playerJsonString.isNullOrBlank()) {
            try {
                val root = json.parseToJsonElement(playerJsonString).jsonObject
                val videoDetails = root["videoDetails"]?.jsonObject
                if (videoDetails != null) {
                    val title = videoDetails["title"]?.jsonPrimitive?.contentOrNull
                    val author = videoDetails["author"]?.jsonPrimitive?.contentOrNull
                    val description = videoDetails["shortDescription"]?.jsonPrimitive?.contentOrNull

                    if (!title.isNullOrBlank() || !description.isNullOrBlank()) {
                        val captionTracks = YouTubeCaptionParser.extractCaptionTracks(playerJsonString)
                        val preferredTrackUrl = YouTubeCaptionParser.pickPreferredCaptionTrack(captionTracks)?.baseUrl

                        return YouTubeData(
                            videoId = videoId,
                            title = title?.trim(),
                            author = author?.trim(),
                            description = description?.trim(),
                            thumbnailUrl = thumbnailUrl,
                            isPlayerResponse = true,
                            captionTracks = captionTracks,
                            preferredCaptionTrackUrl = preferredTrackUrl
                        )
                    }
                }
            } catch (e: Exception) {
                log("Failed to parse ytInitialPlayerResponse: ${e.message}")
            }
        }

        // 2. Fallback to meta tags
        val doc = Jsoup.parse(html)
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name=title]")?.attr("content")?.trim()
            ?: doc.title().trim().removeSuffix(" - YouTube").ifBlank { null }

        val ogDescription = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val author = doc.selectFirst("link[itemprop=name]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name=author]")?.attr("content")?.trim()

        if (ogTitle.isNullOrBlank() && ogDescription.isNullOrBlank()) {
            return null
        }

        return YouTubeData(
            videoId = videoId,
            title = ogTitle,
            author = author,
            description = ogDescription,
            thumbnailUrl = thumbnailUrl,
            isPlayerResponse = false
        )
    }

    private fun extractInitialPlayerResponseJson(html: String): String? {
        // Anchored on the assignment: the page can mention the name earlier (e.g. "= null").
        val match = PLAYER_RESPONSE_ASSIGNMENT.find(html) ?: return null
        val openBrace = match.range.last

        var depth = 0
        var inString = false
        var escape = false

        for (i in openBrace until html.length) {
            val c = html[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        return html.substring(openBrace, i + 1)
                    }
                }
            }
        }
        return null
    }

    /**
     * Fetches and parses timed-text subtitles from a caption track baseUrl.
     */
    open suspend fun fetchCaptionTranscript(trackUrl: String): String? {
        val headers = mapOf(
            "User-Agent" to DESKTOP_USER_AGENT,
            "Accept-Language" to "en",
            "Cookie" to "SOCS=CAI"
        )
        val response = try {
            httpFetcher.get(trackUrl, headers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Caption track fetch failed for $trackUrl: ${e.message}")
            return null
        }

        if (!response.isSuccessful || response.body.isBlank()) {
            return null
        }

        return if (trackUrl.contains("fmt=json3") || response.body.trimStart().startsWith("{")) {
            YouTubeCaptionParser.parseTimedTextJson(response.body)
        } else {
            YouTubeCaptionParser.parseTimedTextXml(response.body)
        }.ifBlank { null }
    }
}
