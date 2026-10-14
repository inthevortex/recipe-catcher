package org.eu.angsumanray.recipecatcher.core.insta

import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

internal object HtmlText {

    /**
     * Text of [element] with `<br>` and paragraph boundaries as line breaks. Source-formatting
     * whitespace inside text nodes is collapsed, as a browser would render it.
     */
    fun withLineBreaks(element: Element): String {
        val sb = StringBuilder()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: org.jsoup.nodes.Node, depth: Int) {
                when {
                    node is TextNode -> sb.append(node.text().replace(Regex("\\s+"), " "))
                    node is Element && node.normalName() == "br" -> sb.append('\n')
                    node is Element && node !== element && node.isBlock && sb.isNotEmpty() -> sb.append('\n')
                }
            }

            override fun tail(node: org.jsoup.nodes.Node, depth: Int) = Unit
        }, element)
        return sb.toString()
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
