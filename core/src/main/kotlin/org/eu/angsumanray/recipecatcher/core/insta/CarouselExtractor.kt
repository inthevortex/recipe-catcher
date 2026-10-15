package org.eu.angsumanray.recipecatcher.core.insta

import org.jsoup.Jsoup

object CarouselExtractor {

    private val DISPLAY_URL_REGEX = Regex(""""display_url"\s*:\s*"([^"]+)"""")
    private val SIDECAR_EDGES_START_REGEX = Regex(""""edge_sidecar_to_children"\s*:\s*\{\s*"edges"\s*:\s*\[""")

    /**
     * Extracts all slide image URLs from Instagram post HTML or embed HTML.
     * Returns URLs in order of appearance.
     */
    fun extractSlideUrls(html: String): List<String> {
        if (html.isBlank()) return emptyList()

        val results = mutableListOf<String>()

        // 1. Check for edge_sidecar_to_children block in scripts
        val edgesBlock = sidecarEdgesBlock(html)
        if (edgesBlock != null) {
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

    /**
     * Returns the body of the `edge_sidecar_to_children.edges` array. Child nodes carry their own nested
     * `edges` arrays (tagged users, captions), so the array end is found by bracket depth, not by regex.
     */
    private fun sidecarEdgesBlock(html: String): String? {
        val start = SIDECAR_EDGES_START_REGEX.find(html)?.range?.last?.plus(1) ?: return null
        var depth = 1
        var inString = false
        var i = start
        while (i < html.length) {
            val c = html[i]
            if (inString) {
                when (c) {
                    '\\' -> i++
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[', '{' -> depth++
                    ']', '}' -> if (--depth == 0) return html.substring(start, i)
                }
            }
            i++
        }
        return null
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
