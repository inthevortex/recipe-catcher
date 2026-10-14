package org.eu.angsumanray.recipecatcher.core.web

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object ReadableTextExtractor {

    const val MAX_CHARACTERS = 12000

    private const val STRIP_SELECTORS =
        "script, style, nav, header, footer, aside, form, input, button, select, textarea, noscript, svg, iframe"

    private const val BLOCK_SELECTORS = "h1, h2, h3, h4, h5, h6, p, li, dt, dd"

    /**
     * Extracts readable text from an HTML document by stripping non-content elements,
     * finding the container element with the most paragraph text, and capping at 12,000 characters.
     */
    fun extract(document: Document): String {
        val cleanDoc = document.clone()
        cleanDoc.select(STRIP_SELECTORS).remove()

        // Candidates come in document order, so a descendant follows its ancestor. Taking it on a
        // tie picks the innermost element that still holds all of the best paragraph text,
        // instead of always ending at <body>.
        val candidates = cleanDoc.select("article, main, [role=main], div, section, body")
        var bestElement: Element? = null
        var maxParagraphLength = -1

        for (candidate in candidates) {
            val totalLength = candidate.select("p").sumOf { it.text().trim().length }
            val best = bestElement
            if (totalLength > maxParagraphLength ||
                (totalLength == maxParagraphLength && best != null && candidate.parents().contains(best))
            ) {
                maxParagraphLength = totalLength
                bestElement = candidate
            }
        }

        val targetElement = bestElement ?: cleanDoc.body() ?: cleanDoc

        // Nested blocks (a <p> inside an <li>) are skipped, so their text is not repeated.
        val blocks = targetElement.select(BLOCK_SELECTORS)
        val blockSet = blocks.toSet()
        val text = if (blocks.isNotEmpty()) {
            blocks.filter { block -> block.parents().none { it in blockSet } }
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        } else {
            targetElement.text().trim()
        }

        return if (text.length > MAX_CHARACTERS) {
            text.substring(0, MAX_CHARACTERS).trimEnd()
        } else {
            text
        }
    }
}
