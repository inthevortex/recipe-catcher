package org.eu.angsumanray.recipecatcher.core.insta

import org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher
import org.jsoup.Jsoup

class PostPageMetaFetchStrategy(
    private val httpFetcher: HttpFetcher,
    private val userAgent: String = EmbedPageFetchStrategy.MOBILE_USER_AGENT
) : PostFetchStrategy {

    override val name: String = "PostPageMeta"

    private val prefixRegex = Regex(
        """^(?:[0-9,KMkm.]+\s+likes?,\s+[0-9,KMkm.]+\s+comments?\s+[-–—]\s+)?(?:@?([a-zA-Z0-9._]+)\s+on\s+[^:]+:\s*|@?([a-zA-Z0-9._]+):\s*)""",
        RegexOption.IGNORE_CASE
    )

    private val titleAuthorRegex = Regex(
        """^(?:(.+?)\s*\(@([a-zA-Z0-9._]+)\)|([a-zA-Z0-9._]+))\s+on\s+Instagram""",
        RegexOption.IGNORE_CASE
    )

    override suspend fun fetch(shortcode: String): PostData? {
        val url = "https://www.instagram.com/p/$shortcode/"
        val headers = mapOf("User-Agent" to userAgent)
        val response = try {
            httpFetcher.get(url, headers)
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

            // Strip surrounding quotes if present: "caption" -> caption
            if (caption.startsWith("\"") && caption.endsWith("\"") && caption.length >= 2) {
                caption = caption.substring(1, caption.length - 1).trim()
            } else if (caption.startsWith("“") && caption.endsWith("”") && caption.length >= 2) {
                caption = caption.substring(1, caption.length - 1).trim()
            }
        }

        return PostData(
            caption = caption?.takeIf { it.isNotBlank() },
            author = author?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() },
            imageUrl = ogImage?.takeIf { it.isNotBlank() }
        )
    }
}
