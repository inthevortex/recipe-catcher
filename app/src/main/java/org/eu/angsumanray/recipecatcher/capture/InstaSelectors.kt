package org.eu.angsumanray.recipecatcher.capture

/**
 * DOM selectors and extraction logic for Instagram comments.
 *
 * Maintenance guide:
 * When Instagram updates its web DOM structure:
 * 1. Open an Instagram post on desktop or mobile browser with developer tools enabled.
 * 2. Inspect the comments section below the caption.
 * 3. Update the selectors below to match the current comment row and comment text elements.
 */
object InstaSelectors {

    val COMMENT_CONTAINER_SELECTORS = listOf(
        "ul._a9ym",
        "div.x9f619 ul li",
        "div[role='button']",
        "ul.x1heor9g li",
        "article ul li"
    )

    val COMMENT_TEXT_SELECTORS = listOf(
        "span._a9zs",
        "span.x193iq5w",
        "span._a9zr",
        "span"
    )

    val RECIPE_CUES = listOf(
        "recipe in comment",
        "recipe below",
        "recipe in the comments",
        "full recipe in comment",
        "ingredients in comment",
        "recipe in 1st comment",
        "recipe in first comment",
        "recipe pinned",
        "check comments"
    )

    fun hasRecipeInCommentsCue(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lower = text.lowercase()
        return RECIPE_CUES.any { lower.contains(it) }
    }

    /**
     * JavaScript snippet executed inside the post's WebView to read the top comment.
     * Looks through candidate comment elements, skipping the author's initial caption if present,
     * and returns the first comment text.
     */
    fun buildExtractionJs(): String {
        return """
            (function() {
                try {
                    // Try looking for comment text spans
                    var candidates = document.querySelectorAll("span._a9zs, span.x193iq5w, span._a9zr, ul li span");
                    for (var i = 0; i < candidates.length; i++) {
                        var el = candidates[i];
                        var text = (el.innerText || el.textContent || '').trim();
                        // Ignore extremely short reactions like emojis only
                        if (text.length > 15) {
                            return text;
                        }
                    }
                    // Fallback: check all list items in article
                    var lis = document.querySelectorAll("article ul li");
                    if (lis.length > 1) {
                        // lis[0] is often caption; lis[1] is first comment
                        var second = lis[1];
                        var t = (second.innerText || second.textContent || '').trim();
                        if (t.length > 10) return t;
                    }
                } catch(e) {}
                return null;
            })();
        """.trimIndent()
    }
}
