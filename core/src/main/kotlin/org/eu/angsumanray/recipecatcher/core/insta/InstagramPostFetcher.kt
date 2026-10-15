package org.eu.angsumanray.recipecatcher.core.insta

import kotlinx.coroutines.CancellationException
import org.jsoup.parser.Parser

/**
 * Runs the strategies in order and stops at the first non-empty caption. Image URL and author
 * are merged from whichever strategy supplies them first. The returned caption is HTML-unescaped
 * with line breaks kept; hashtag-only trailing lines are left in so the row keeps the full text,
 * and [stripTrailingHashtags] removes them before the caption goes to the LLM.
 */
class InstagramPostFetcher(
    private val strategies: List<PostFetchStrategy>,
    private val log: (String) -> Unit = {}
) {

    suspend fun fetch(shortcode: String): PostData? {
        var winningCaption: String? = null
        var winningAuthor: String? = null
        var mergedImageUrl: String? = null
        var mergedSlideUrls: List<String> = emptyList()

        for (strategy in strategies) {
            val result = try {
                strategy.fetch(shortcode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Instagram strategy ${strategy.name} failed for $shortcode: ${e.message}")
                null
            } ?: continue

            if (mergedImageUrl == null && !result.imageUrl.isNullOrBlank()) {
                mergedImageUrl = result.imageUrl
            }
            if (winningAuthor == null && !result.author.isNullOrBlank()) {
                winningAuthor = result.author
            }
            if (mergedSlideUrls.isEmpty() && result.slideImageUrls.isNotEmpty()) {
                mergedSlideUrls = result.slideImageUrls
            }
            if (winningCaption == null && !result.caption.isNullOrBlank()) {
                winningCaption = result.caption
                log("Instagram caption for $shortcode from strategy ${strategy.name}")
            }
            // Later strategies are only worth a request while the caption or image is still missing.
            if (winningCaption != null && mergedImageUrl != null) break
        }

        val caption = winningCaption?.let { unescapeCaption(it) }?.takeIf { it.isNotBlank() }
            ?: return null

        return PostData(
            caption = caption,
            author = winningAuthor,
            imageUrl = mergedImageUrl,
            slideImageUrls = mergedSlideUrls
        )
    }

    companion object {
        fun unescapeCaption(raw: String): String =
            Parser.unescapeEntities(raw, false)
                .replace("\r\n", "\n")
                .lines()
                .joinToString("\n") { it.trimEnd() }
                .trim()

        /** Drops hashtag-only and blank lines from the end of a caption. */
        fun stripTrailingHashtags(caption: String): String {
            val lines = caption.lines()
            var lastKept = lines.size - 1
            while (lastKept >= 0) {
                val line = lines[lastKept].trim()
                if (line.isEmpty() || isHashtagOnlyLine(line)) lastKept-- else break
            }
            return lines.subList(0, lastKept + 1).joinToString("\n").trim()
        }

        private fun isHashtagOnlyLine(line: String): Boolean {
            val tokens = line.split("\\s+".toRegex()).filter { it.isNotBlank() }
            return tokens.isNotEmpty() && tokens.all { it.startsWith("#") }
        }

        private const val QUANTITY = """(?:\d+(?:[.,/]\d+)?|[½¼¾⅓⅔⅛])"""

        // A number followed by a unit anywhere in the line ("add 2 tbsp butter").
        private val UNIT_QUANTITY_REGEX = Regex(
            """$QUANTITY\s*(?:g|gm|gms|grams?|kg|ml|l|litres?|liters?|tsp|tbsp|teaspoons?|tablespoons?|cups?|oz|lbs?|pinch(?:es)?|pieces?|pcs?|cloves?|cans?|slices?|tb|ts)\b""",
            RegexOption.IGNORE_CASE
        )

        // A bare count opening an ingredient line ("2 onions", "- 3 eggs"), but not list numbering ("1. Swipe").
        private val LEADING_COUNT_REGEX = Regex("""^[-•*]?\s*$QUANTITY\s+\p{L}""")

        /** Quick recipe heuristic: whether the caption has fewer than 3 lines that look like ingredient quantities. */
        fun hasFewerThan3QuantityLines(caption: String?): Boolean {
            if (caption.isNullOrBlank()) return true
            val count = caption.lines().count { line ->
                val trimmed = line.trim()
                UNIT_QUANTITY_REGEX.containsMatchIn(trimmed) || LEADING_COUNT_REGEX.containsMatchIn(trimmed)
            }
            return count < 3
        }
    }
}
