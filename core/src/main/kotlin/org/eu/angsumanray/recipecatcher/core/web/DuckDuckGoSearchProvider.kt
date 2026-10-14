package org.eu.angsumanray.recipecatcher.core.web

import org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher
import org.eu.angsumanray.recipecatcher.core.ports.SearchResult
import org.eu.angsumanray.recipecatcher.core.ports.WebSearchProvider
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class DuckDuckGoSearchProvider(
    private val httpFetcher: HttpFetcher,
    private val logger: ((String) -> Unit)? = null
) : WebSearchProvider {

    override suspend fun search(query: String, maxResults: Int): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val searchUrl = "https://html.duckduckgo.com/html/?q=$encodedQuery"

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        return try {
            val response = httpFetcher.get(searchUrl, headers)
            if (!response.isSuccessful) {
                logger?.invoke("DuckDuckGo search failed with code ${response.statusCode}")
                return emptyList()
            }
            parseHtmlResults(response.body, maxResults)
        } catch (e: Exception) {
            logger?.invoke("DuckDuckGo search error: ${e.message}")
            emptyList()
        }
    }

    internal fun parseHtmlResults(html: String, maxResults: Int = 3): List<SearchResult> {
        val doc = Jsoup.parse(html)
        val resultElements = doc.select(".result, .web-result")
        val results = mutableListOf<SearchResult>()

        for (element in resultElements) {
            val titleEl = element.selectFirst(".result__title a, .result__a, h2 a") ?: continue
            val rawHref = titleEl.attr("href").trim()
            val cleanUrl = extractCleanUrl(rawHref) ?: continue

            // Exclude video platforms
            val lowerUrl = cleanUrl.lowercase()
            if (lowerUrl.contains("youtube.com") || lowerUrl.contains("youtu.be") ||
                lowerUrl.contains("tiktok.com") || lowerUrl.contains("vimeo.com") ||
                lowerUrl.contains("instagram.com/reel")
            ) {
                continue
            }

            val title = titleEl.text().trim()
            if (title.isEmpty()) continue

            val snippetEl = element.selectFirst(".result__snippet")
            val snippet = snippetEl?.text()?.trim() ?: ""

            val host = try {
                URI(cleanUrl).host?.removePrefix("www.") ?: ""
            } catch (e: Exception) {
                ""
            }

            results.add(
                SearchResult(
                    title = title,
                    url = cleanUrl,
                    snippet = snippet,
                    site = host
                )
            )

            if (results.size >= maxResults) break
        }

        return results
    }

    private fun extractCleanUrl(rawHref: String): String? {
        if (rawHref.isEmpty()) return null

        // Handle DuckDuckGo redirect: //duckduckgo.com/l/?uddg=https%3A%2F%2F...
        if (rawHref.contains("uddg=")) {
            val uddgPart = rawHref.substringAfter("uddg=").substringBefore("&")
            return try {
                val decoded = URLDecoder.decode(uddgPart, StandardCharsets.UTF_8.name())
                if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else null
            } catch (e: Exception) {
                null
            }
        }

        val url = if (rawHref.startsWith("//")) "https:$rawHref" else rawHref
        return if (url.startsWith("http://") || url.startsWith("https://")) url else null
    }
}
