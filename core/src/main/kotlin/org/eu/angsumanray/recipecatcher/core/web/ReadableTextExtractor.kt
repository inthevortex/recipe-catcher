package org.eu.angsumanray.recipecatcher.core.web

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object ReadableTextExtractor {

    const val MAX_CHARACTERS = 12000

    private val STRIP_SELECTORS = "script, style, nav, header, footer, aside, form, noscript, svg, iframe"

    /**
     * Extracts readable text from an HTML document by stripping non-content elements,
     * finding the container element with the most paragraph text, and capping at 12,000 characters.
     */
    fun extract(document: Document): String {
        val cleanDoc = document.clone()
        cleanDoc.select(STRIP_SELECTORS).remove()

        // Candidates for main recipe content container
        val candidates = cleanDoc.select("article, main, [role=main], div, section, body")
        var bestElement: Element? = null
        var maxParagraphLength = -1

        for (candidate in candidates) {
            val paragraphs = candidate.select("p")
            val totalLength = paragraphs.sumOf { it.text().trim().length }
            if (totalLength > maxParagraphLength) {
                maxParagraphLength = totalLength
                bestElement = candidate
            }
        }

        val targetElement = bestElement ?: cleanDoc.body() ?: cleanDoc

        // Format lines by preserving block structure (headings, paragraphs, lists)
        val blocks = targetElement.select("h1, h2, h3, h4, h5, h6, p, li, dt, dd")
        val text = if (blocks.isNotEmpty()) {
            blocks.map { it.text().trim() }
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
