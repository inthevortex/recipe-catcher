package org.eu.angsumanray.recipecatcher.core.web

import kotlinx.coroutines.CancellationException
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

    companion object {
        private val SKIPPED_HOSTS = listOf(
            "duckduckgo.com", "youtube.com", "youtu.be", "tiktok.com", "vimeo.com", "instagram.com"
        )
    }

    override suspend fun search(query: String, maxResults: Int): List<SearchResult> {
        if (query.isBlank() || maxResults <= 0) return emptyList()
        val encodedQuery = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger?.invoke("DuckDuckGo search error: ${e.message}")
            emptyList()
        }
    }

    /** Organic results only: ads, DuckDuckGo's own links, video hosts and repeated URLs are skipped. */
    internal fun parseHtmlResults(html: String, maxResults: Int = 3): List<SearchResult> {
        if (html.isBlank() || maxResults <= 0) return emptyList()
        val doc = Jsoup.parse(html)
        val results = mutableListOf<SearchResult>()
        val seenUrls = mutableSetOf<String>()

        for (element in doc.select(".result, .web-result")) {
            if (element.hasClass("result--ad") || element.parents().any { it.hasClass("result--ad") }) continue

            val titleEl = element.selectFirst(".result__title a, .result__a, h2 a") ?: continue
            val cleanUrl = extractCleanUrl(titleEl.attr("href").trim()) ?: continue
            val host = try {
                URI(cleanUrl).host?.lowercase()
            } catch (e: Exception) {
                null
            } ?: continue
            if (SKIPPED_HOSTS.any { host == it || host.endsWith(".$it") }) continue
            if (!seenUrls.add(cleanUrl.removeSuffix("/"))) continue

            val title = titleEl.text().trim()
            if (title.isEmpty()) continue

            results.add(
                SearchResult(
                    title = title,
                    url = cleanUrl,
                    snippet = element.selectFirst(".result__snippet")?.text()?.trim().orEmpty(),
                    site = host.removePrefix("www.")
                )
            )
            if (results.size >= maxResults) break
        }

        return results
    }

    private fun extractCleanUrl(rawHref: String): String? {
        if (rawHref.isEmpty()) return null

        // DuckDuckGo redirect: //duckduckgo.com/l/?uddg=https%3A%2F%2F...
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
