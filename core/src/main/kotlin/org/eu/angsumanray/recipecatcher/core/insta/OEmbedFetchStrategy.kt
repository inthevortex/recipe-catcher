package org.eu.angsumanray.recipecatcher.core.insta

import kotlinx.coroutines.CancellationException
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
        } catch (e: CancellationException) {
            throw e
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

        // Captioned embeds carry the caption in a <p> inside the blockquote; the "View this post"
        // and "A post shared by" boilerplate is never a caption. Uncaptioned embeds only have it in title.
        val htmlCaption = parsed.html?.takeIf { it.isNotBlank() }?.let { html ->
            Jsoup.parse(html).select("blockquote p")
                .map { HtmlText.withLineBreaks(it) }
                .firstOrNull { text ->
                    text.isNotBlank() &&
                        !text.startsWith("A post shared by", ignoreCase = true) &&
                        !text.startsWith("View this post", ignoreCase = true)
                }
        }
        val caption = htmlCaption ?: parsed.title

        val author = parsed.author_name?.trim()?.removePrefix("@")
        return PostData(
            caption = caption?.takeIf { it.isNotBlank() },
            author = author?.takeIf { it.isNotBlank() },
            imageUrl = parsed.thumbnail_url?.takeIf { it.isNotBlank() }
        )
    }
}
