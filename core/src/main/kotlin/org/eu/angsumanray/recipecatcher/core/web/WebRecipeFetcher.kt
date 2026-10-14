package org.eu.angsumanray.recipecatcher.core.web

import kotlinx.coroutines.CancellationException
import org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

class WebRecipeFetcher(
    private val httpFetcher: HttpFetcher,
    private val log: (String) -> Unit = {}
) {
    companion object {
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        const val MAX_BODY_BYTES = 5 * 1024 * 1024 // 5 MB

        private val CLOUDFLARE_MARKERS = listOf(
            "cf-browser-verification",
            "challenge-platform",
            "attention required! | cloudflare",
            "just a moment...",
            "checking your browser before accessing"
        )
    }

    suspend fun fetch(url: String): WebRecipeData? {
        val headers = mapOf(
            "User-Agent" to DESKTOP_USER_AGENT,
            "Accept-Language" to "en",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        val response = try {
            httpFetcher.get(url, headers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Web fetch request failed for $url: ${e.message}")
            return null
        }

        if (response.statusCode == 403 || response.statusCode == 503) {
            throw WebFetchBlockedException(url, response.statusCode, "HTTP ${response.statusCode} - Site access restricted or challenged")
        }

        if (!response.isSuccessful) {
            log("Web fetch received HTTP ${response.statusCode} for $url")
            return null
        }

        val body = response.body
        val lowerBody = body.lowercase()
        if (CLOUDFLARE_MARKERS.any { lowerBody.contains(it) }) {
            throw WebFetchBlockedException(url, response.statusCode, "Cloudflare challenge detected")
        }

        val truncatedBody = if (body.length > MAX_BODY_BYTES) {
            body.substring(0, MAX_BODY_BYTES)
        } else {
            body
        }

        return parseHtml(truncatedBody, url)
    }

    /**
     * Parses an HTML string into [WebRecipeData]. Usable by both direct HTTP fetching
     * and the foreground WebView fallback.
     */
    fun parseHtml(html: String, url: String): WebRecipeData? {
        val doc = Jsoup.parse(html, url)
        val host = extractHost(url)

        val ogSiteName = extractMeta(doc, "og:site_name")
        val author = ogSiteName?.ifBlank { null } ?: host

        val ogImage = extractMeta(doc, "og:image")
        val ogTitle = extractMeta(doc, "og:title") ?: doc.title().trim().ifBlank { null }

        // 1. Try schema.org JSON-LD
        val jsonLd = JsonLdParser.findRecipe(doc)
        if (jsonLd != null) {
            val title = jsonLd.title ?: ogTitle
            val imageUrl = jsonLd.imageUrl ?: ogImage
            return WebRecipeData(
                title = title,
                author = author,
                imageUrl = imageUrl,
                rawLdJson = jsonLd.normalizedJsonString,
                readableText = null,
                sourceText = jsonLd.normalizedJsonString,
                isJsonLd = true,
                directMapping = jsonLd.directMapping.copy(
                    title = title,
                    cuisine = jsonLd.directMapping.cuisine
                )
            )
        }

        // 2. Fallback to readable text extraction
        val readableText = ReadableTextExtractor.extract(doc)
        if (readableText.isBlank()) {
            return null
        }

        return WebRecipeData(
            title = ogTitle,
            author = author,
            imageUrl = ogImage,
            rawLdJson = null,
            readableText = readableText,
            sourceText = readableText,
            isJsonLd = false,
            directMapping = null
        )
    }

    private fun extractMeta(doc: Document, propertyOrName: String): String? {
        return doc.selectFirst("meta[property=$propertyOrName]")?.attr("content")?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=$propertyOrName]")?.attr("content")?.trim()?.ifBlank { null }
    }

    private fun extractHost(url: String): String? {
        return try {
            val uri = URI(url)
            uri.host?.removePrefix("www.")?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }
}
