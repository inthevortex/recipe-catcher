package org.eu.angsumanray.recipecatcher.core.insta

import org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class EmbedPageFetchStrategy(
    private val httpFetcher: HttpFetcher,
    private val userAgent: String = MOBILE_USER_AGENT
) : PostFetchStrategy {

    companion object {
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
    }

    override val name: String = "EmbedPage"

    override suspend fun fetch(shortcode: String): PostData? {
        val url = "https://www.instagram.com/p/$shortcode/embed/captioned/"
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

        // Find author
        val authorElement = doc.selectFirst(".CaptionUsername")
            ?: doc.selectFirst(".Username")
            ?: doc.selectFirst(".Header .UsernameText")
            ?: doc.selectFirst("a[class*=Username]")
        val author = authorElement?.text()?.trim()?.removePrefix("@")

        // Find caption
        val captionElement = doc.selectFirst(".Caption")
            ?: doc.selectFirst(".CaptionText")
            ?: doc.selectFirst("div.Caption")
            ?: doc.selectFirst("[class*=Caption]")

        var caption: String? = null
        if (captionElement != null) {
            val cloned = captionElement.clone()
            // Remove the username element inside caption if present
            cloned.select(".CaptionUsername, .CaptionComments, [class*=Username]").remove()
            caption = extractTextWithNewlines(cloned)
        }

        // Find image
        val imageElement = doc.selectFirst("img.EmbeddedMediaImage")
            ?: doc.selectFirst("img[src*='cdninstagram']")
            ?: doc.selectFirst("img[src*='fbcdn']")
            ?: doc.selectFirst(".EmbeddedMedia img")
            ?: doc.selectFirst("img")
        val imageUrl = imageElement?.attr("src")?.takeIf { it.isNotBlank() }

        return PostData(
            caption = caption?.takeIf { it.isNotBlank() },
            author = author?.takeIf { it.isNotBlank() },
            imageUrl = imageUrl
        )
    }

    private fun extractTextWithNewlines(element: Element): String {
        element.select("br").after("\n")
        element.select("p").prepend("\n")
        return element.wholeText().lines().map { it.trim() }.joinToString("\n").trim()
    }
}
