package org.eu.angsumanray.recipecatcher.core.insta

import kotlinx.coroutines.CancellationException
import org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher
import org.jsoup.Jsoup

class PostPageMetaFetchStrategy(
    private val httpFetcher: HttpFetcher,
    private val userAgent: String = EmbedPageFetchStrategy.MOBILE_USER_AGENT
) : PostFetchStrategy {

    override val name: String = "PostPageMeta"

    // "1,450 likes, 82 comments - handle on October 10, 2026: " (comment count optional).
    // Requires the counts or a dated "handle on <date>:" so a caption starting "Ingredients:" survives.
    private val prefixRegex = Regex(
        """^(?:[0-9][0-9,.]*\s*[KMB]?\s+likes?(?:,\s*[0-9][0-9,.]*\s*[KMB]?\s+comments?)?\s*[-–—]\s*@?([a-zA-Z0-9._]+)\s+on\s+[^:]{1,40}:\s*|@?([a-zA-Z0-9._]+)\s+on\s+[A-Za-z]+\s+\d{1,2},\s+\d{4}:\s*)""",
        RegexOption.IGNORE_CASE
    )

    private val quotedCaptionRegex = Regex("""^["“](.*)["”]\s*\.?$""", RegexOption.DOT_MATCHES_ALL)

    private val titleAuthorRegex = Regex(
        """^(?:(.+?)\s*\(@([a-zA-Z0-9._]+)\)|([a-zA-Z0-9._]+))\s+on\s+Instagram""",
        RegexOption.IGNORE_CASE
    )

    override suspend fun fetch(shortcode: String): PostData? {
        val url = "https://www.instagram.com/p/$shortcode/"
        val headers = mapOf("User-Agent" to userAgent)
        val response = try {
            httpFetcher.get(url, headers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (!response.isSuccessful) return null
        return parseHtml(response.body)
    }

    fun parseHtml(html: String): PostData? {
        val doc = Jsoup.parse(html)
        val ogDescription = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("title")?.text()

        var author: String? = null
        if (!ogTitle.isNullOrBlank()) {
            val titleMatch = titleAuthorRegex.find(ogTitle)
            if (titleMatch != null) {
                author = titleMatch.groupValues[2].takeIf { it.isNotBlank() }
                    ?: titleMatch.groupValues[3].takeIf { it.isNotBlank() }
                    ?: titleMatch.groupValues[1].takeIf { it.isNotBlank() }
            }
        }

        var caption: String? = null
        if (!ogDescription.isNullOrBlank()) {
            val match = prefixRegex.find(ogDescription)
            if (match != null) {
                if (author == null) {
                    author = match.groupValues[1].takeIf { it.isNotBlank() }
                        ?: match.groupValues[2].takeIf { it.isNotBlank() }
                }
                caption = ogDescription.substring(match.range.last + 1).trim()
            } else {
                caption = ogDescription.trim()
            }

            // The caption is quoted and usually followed by a period: "caption".
            quotedCaptionRegex.find(caption)?.let { caption = it.groupValues[1].trim() }
        }

        val slideUrls = CarouselExtractor.extractSlideUrls(html)
        val coverImage = ogImage?.takeIf { it.isNotBlank() } ?: slideUrls.firstOrNull()
        val slides = if (slideUrls.size > 1) slideUrls.drop(1).take(4) else emptyList()

        return PostData(
            caption = caption?.takeIf { it.isNotBlank() },
            author = author?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() },
            imageUrl = coverImage,
            slideImageUrls = slides
        )
    }
}
