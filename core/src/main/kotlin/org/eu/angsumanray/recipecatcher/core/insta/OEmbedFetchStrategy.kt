package org.eu.angsumanray.recipecatcher.core.insta

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher
import org.jsoup.Jsoup

@Serializable
private data class OEmbedResponse(
    val title: String? = null,
    val author_name: String? = null,
    val author_url: String? = null,
    val html: String? = null,
    val thumbnail_url: String? = null
)

class OEmbedFetchStrategy(
    private val httpFetcher: HttpFetcher,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : PostFetchStrategy {

    override val name: String = "oEmbed"

    override suspend fun fetch(shortcode: String): PostData? {
        val url = "https://graph.facebook.com/v19.0/instagram_oembed?url=https://www.instagram.com/p/$shortcode/&omitscript=true"
        val response = try {
            httpFetcher.get(url)
        } catch (e: Exception) {
            return null
        }
        if (!response.isSuccessful) return null
        return parseResponse(response.body)
    }

    fun parseResponse(jsonBody: String): PostData? {
        val parsed = try {
            json.decodeFromString<OEmbedResponse>(jsonBody)
        } catch (e: Exception) {
            return null
        }

        var caption: String? = null
        if (!parsed.html.isNullOrBlank()) {
            val doc = Jsoup.parse(parsed.html)
            val paragraphs = doc.select("blockquote p")
            val captionP = paragraphs.firstOrNull { p ->
                val text = p.text().trim()
                !text.startsWith("A post shared by", ignoreCase = true) &&
                        !text.startsWith("View this post", ignoreCase = true)
            }
            caption = captionP?.text() ?: doc.select("blockquote").text()
        }
        if (caption.isNullOrBlank() && !parsed.title.isNullOrBlank()) {
            caption = parsed.title
        }

        val author = parsed.author_name?.removePrefix("@")
        return PostData(
            caption = caption?.takeIf { it.isNotBlank() },
            author = author?.takeIf { it.isNotBlank() },
            imageUrl = parsed.thumbnail_url?.takeIf { it.isNotBlank() }
        )
    }
}
