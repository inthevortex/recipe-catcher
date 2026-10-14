package org.eu.angsumanray.recipecatcher.core.insta

import org.jsoup.parser.Parser

class InstagramPostFetcher(
    private val strategies: List<PostFetchStrategy>
) {

    suspend fun fetch(shortcode: String): PostData? {
        var winningCaption: String? = null
        var winningAuthor: String? = null
        var mergedImageUrl: String? = null

        for (strategy in strategies) {
            val result = try {
                strategy.fetch(shortcode)
            } catch (e: Exception) {
                null
            } ?: continue

            if (mergedImageUrl == null && !result.imageUrl.isNullOrBlank()) {
                mergedImageUrl = result.imageUrl
            }
            if (winningAuthor == null && !result.author.isNullOrBlank()) {
                winningAuthor = result.author
            }
            if (winningCaption == null && !result.caption.isNullOrBlank()) {
                winningCaption = result.caption
                // If we already have an image, or later strategies aren't needed, break
                if (mergedImageUrl != null) {
                    break
                }
            }
        }

        if (winningCaption == null) {
            return null
        }

        val cleanedCaption = cleanCaption(winningCaption)

        return PostData(
            caption = cleanedCaption,
            author = winningAuthor,
            imageUrl = mergedImageUrl
        )
    }

    companion object {
        fun cleanCaption(raw: String): String {
            val unescaped = Parser.unescapeEntities(raw, false)
            val lines = unescaped.lines()

            var lastNonHashtagIdx = lines.size - 1
            while (lastNonHashtagIdx >= 0) {
                val line = lines[lastNonHashtagIdx].trim()
                if (line.isEmpty() || isHashtagOnlyLine(line)) {
                    lastNonHashtagIdx--
                } else {
                    break
                }
            }

            return if (lastNonHashtagIdx < 0) {
                ""
            } else {
                lines.subList(0, lastNonHashtagIdx + 1).joinToString("\n").trim()
            }
        }

        private fun isHashtagOnlyLine(line: String): Boolean {
            val tokens = line.split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return false
            return tokens.all { it.startsWith("#") }
        }
    }
}
