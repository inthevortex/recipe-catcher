package org.eu.angsumanray.recipecatcher.core.insta

import kotlinx.coroutines.CancellationException
import org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher
import org.jsoup.Jsoup

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

        val authorElement = doc.selectFirst(".CaptionUsername")
            ?: doc.selectFirst(".Username")
            ?: doc.selectFirst(".Header .UsernameText")
            ?: doc.selectFirst("a[class*=Username]")
        val author = authorElement?.text()?.trim()?.removePrefix("@")

        val captionElement = doc.selectFirst(".Caption")
            ?: doc.selectFirst(".CaptionText")

        var caption: String? = null
        if (captionElement != null) {
            val cloned = captionElement.clone()
            cloned.select(".CaptionUsername, .CaptionComments, [class*=Username]").remove()
            caption = HtmlText.withLineBreaks(cloned)
        }

        // Only media images: the header also holds the author's avatar, served from the same CDN.
        val imageElement = doc.selectFirst("img.EmbeddedMediaImage")
            ?: doc.selectFirst(".EmbeddedMedia img")
        val imageUrl = imageElement?.attr("src")?.takeIf { it.isNotBlank() }

        return PostData(
            caption = caption?.takeIf { it.isNotBlank() },
            author = author?.takeIf { it.isNotBlank() },
            imageUrl = imageUrl
        )
    }
}
