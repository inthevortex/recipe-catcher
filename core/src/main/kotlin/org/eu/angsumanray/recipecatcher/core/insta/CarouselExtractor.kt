package org.eu.angsumanray.recipecatcher.core.insta

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

object CarouselExtractor {

    // Regex to match JSON strings containing display_url or image candidate URLs
    private val DISPLAY_URL_REGEX = Regex(""""display_url"\s*:\s*"([^"]+)"""")
    private val DISPLAY_SRC_REGEX = Regex(""""display_resources"\s*:\s*\[.*?"src"\s*:\s*"([^"]+)"""")
    private val SIDECAR_CHILDREN_REGEX = Regex(""""edge_sidecar_to_children"\s*:\s*\{\s*"edges"\s*:\s*\[(.*?)\]\s*\}""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Extracts all slide image URLs from Instagram post HTML or embed HTML.
     * Returns URLs in order of appearance.
     */
    fun extractSlideUrls(html: String): List<String> {
        if (html.isBlank()) return emptyList()

        val results = mutableListOf<String>()

        // 1. Check for edge_sidecar_to_children block in scripts
        val sidecarMatch = SIDECAR_CHILDREN_REGEX.find(html)
        if (sidecarMatch != null) {
            val edgesBlock = sidecarMatch.groupValues[1]
            for (match in DISPLAY_URL_REGEX.findAll(edgesBlock)) {
                val rawUrl = unescapeJsonUrl(match.groupValues[1])
                if (isValidImageUrl(rawUrl)) {
                    results.add(rawUrl)
                }
            }
        }

        // 2. Check for general display_url in JSON if sidecar didn't find multiple
        if (results.size <= 1) {
            for (match in DISPLAY_URL_REGEX.findAll(html)) {
                val rawUrl = unescapeJsonUrl(match.groupValues[1])
                if (isValidImageUrl(rawUrl)) {
                    results.add(rawUrl)
                }
            }
        }

        // 3. Fallback: Parse HTML DOM with Jsoup for carousel/slides or EmbeddedMedia images
        if (results.size <= 1) {
            try {
                val doc = Jsoup.parse(html)
                val images = doc.select("img.EmbeddedMediaImage, .EmbeddedMedia img, article img, .Carousel img, ul.slides img")
                for (img in images) {
                    val src = img.attr("src").takeIf { it.isNotBlank() }
                        ?: img.attr("data-src").takeIf { it.isNotBlank() }
                    if (src != null && isValidImageUrl(src)) {
                        results.add(src)
                    }
                }
            } catch (_: Exception) {
                // Ignore HTML parsing errors
            }
        }

        return results.distinct()
    }

    private fun isValidImageUrl(url: String): Boolean {
        return (url.startsWith("http://") || url.startsWith("https://")) &&
            !url.contains("avatar", ignoreCase = true) &&
            !url.contains("150x150", ignoreCase = true)
    }

    private fun unescapeJsonUrl(escaped: String): String {
        return escaped
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
    }
}
