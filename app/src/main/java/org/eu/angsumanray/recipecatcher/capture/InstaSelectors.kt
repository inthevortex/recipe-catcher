package org.eu.angsumanray.recipecatcher.capture

/**
 * DOM selectors and extraction logic for Instagram comments. [buildExtractionJs] is generated from
 * these lists, so they are the only place to change when Instagram's markup moves.
 *
 * To refresh them:
 * 1. Log in with the app's WebView user agent (a mobile Chrome UA) and open a post that has comments.
 * 2. In remote DevTools (chrome://inspect), find the first comment below the caption.
 * 3. Put a selector matching each comment row in [COMMENT_CONTAINER_SELECTORS] and one matching
 *    the comment's text inside a row in [COMMENT_TEXT_SELECTORS], most specific first.
 * 4. Keep [CAPTION_SELECTORS] pointing at the caption, so it is never returned as the comment.
 */
object InstaSelectors {

    val COMMENT_CONTAINER_SELECTORS = listOf(
        "ul._a9ym > li",
        "ul.x1heor9g li",
        "article ul li",
        "div[role='dialog'] ul li",
        "ul > div > li",
        "div[role='presentation'] ul li",
        "div.x1lliihq"
    )

    val COMMENT_TEXT_SELECTORS = listOf(
        "span._a9zs",
        "span._aacl",
        "span.x193iq5w",
        "span[dir='auto']"
    )

    val CAPTION_SELECTORS = listOf(
        "h1",
        "div._a9zs > span"
    )

    /** Shorter texts are usually emoji reactions or usernames. */
    const val MIN_COMMENT_LENGTH = 15

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
     * JavaScript run inside the post's WebView. Returns the text of the first comment row that
     * isn't the caption, or null when the comments haven't rendered (or the markup changed).
     */
    fun buildExtractionJs(): String {
        fun jsArray(items: List<String>) = items.joinToString(prefix = "[", postfix = "]") { "'" + it.replace("'", "\\'") + "'" }
        return """
            (function() {
                try {
                    // Try expanding collapsed comments on mobile web layout
                    var clickable = document.querySelectorAll('button, div[role="button"], a, span');
                    for (var b = 0; b < clickable.length; b++) {
                        var txt = (clickable[b].innerText || clickable[b].textContent || '').toLowerCase().trim();
                        if ((txt.indexOf('view all') >= 0 || txt.indexOf('view') >= 0) && txt.indexOf('comment') >= 0) {
                            clickable[b].click();
                            break;
                        }
                    }
                    var containers = ${jsArray(COMMENT_CONTAINER_SELECTORS)};
                    var texts = ${jsArray(COMMENT_TEXT_SELECTORS)};
                    var captionSelectors = ${jsArray(CAPTION_SELECTORS)};
                    var captions = [];
                    captionSelectors.forEach(function(sel) {
                        document.querySelectorAll(sel).forEach(function(el) {
                            var t = (el.innerText || el.textContent || '').trim();
                            if (t) captions.push(t);
                        });
                    });
                    function isCaption(t) {
                        for (var c = 0; c < captions.length; c++) {
                            if (captions[c] === t || captions[c].indexOf(t) >= 0 || t.indexOf(captions[c]) >= 0) return true;
                        }
                        return false;
                    }
                    for (var i = 0; i < containers.length; i++) {
                        var rows = document.querySelectorAll(containers[i]);
                        for (var r = 0; r < rows.length; r++) {
                            for (var j = 0; j < texts.length; j++) {
                                var el = rows[r].querySelector(texts[j]);
                                if (!el) continue;
                                var text = (el.innerText || el.textContent || '').trim();
                                if (text.length >= $MIN_COMMENT_LENGTH && !isCaption(text)) return text;
                            }
                        }
                    }
                } catch (e) {}
                return null;
            })();
        """.trimIndent()
    }
}
