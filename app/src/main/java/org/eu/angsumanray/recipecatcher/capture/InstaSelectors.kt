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
        "h1"
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
    fun buildExtractionJs(targetCommentId: String? = null, knownCaption: String? = null): String {
        fun jsArray(items: List<String>) = items.joinToString(prefix = "[", postfix = "]") { "'" + it.replace("'", "\\'") + "'" }
        val targetIdJs = if (!targetCommentId.isNullOrBlank()) "'$targetCommentId'" else "null"
        val knownCapJs = if (!knownCaption.isNullOrBlank()) {
            "'" + knownCaption.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", "") + "'"
        } else {
            "null"
        }

        return """
            (function() {
                try {
                    var targetCommentId = $targetIdJs;
                    var knownCaption = $knownCapJs;

                    // Try expanding collapsed comments on mobile web layout
                    var clickable = document.querySelectorAll('button, div[role="button"], a, span');
                    for (var b = 0; b < clickable.length; b++) {
                        var txt = (clickable[b].innerText || clickable[b].textContent || '').toLowerCase().trim();
                        if ((txt.indexOf('view all') >= 0 || txt.indexOf('view') >= 0) && txt.indexOf('comment') >= 0) {
                            clickable[b].click();
                            break;
                        }
                    }

                    function isCaption(t) {
                        if (!t) return true;
                        if (knownCaption && (t === knownCaption || knownCaption.indexOf(t) >= 0 || t.indexOf(knownCaption) >= 0)) return true;
                        var h1 = document.querySelector('h1');
                        if (h1) {
                            var h1Text = (h1.innerText || h1.textContent || '').trim();
                            if (h1Text && (t === h1Text || h1Text.indexOf(t) >= 0 || t.indexOf(h1Text) >= 0)) return true;
                        }
                        return false;
                    }

                    // 1. If a specific comment ID was targeted, search for elements referencing it
                    if (targetCommentId) {
                        var targetEl = document.querySelector('[data-comment-id="' + targetCommentId + '"]') ||
                                       document.querySelector('a[href*="' + targetCommentId + '"]');
                        if (targetEl) {
                            var row = targetEl.closest('li') || targetEl.closest('div.x1lliihq') || targetEl.closest('div[role="presentation"]') || targetEl;
                            var textEl = row.querySelector('span[dir="auto"], span._a9zs, span._aacl, span.x193iq5w') || row;
                            var targetText = (textEl.innerText || textEl.textContent || '').trim();
                            if (targetText.length >= 10 && !isCaption(targetText)) return targetText;
                        }
                    }

                    // 2. Look for pinned comments
                    var svgs = document.querySelectorAll('svg[aria-label*="Pin"], svg[aria-label*="pin"], [aria-label*="Pinned"], [aria-label*="pinned"]');
                    for (var p = 0; p < svgs.length; p++) {
                        var pinRow = svgs[p].closest('li') || svgs[p].closest('div.x1lliihq');
                        if (pinRow) {
                            var pinTextEl = pinRow.querySelector('span[dir="auto"], span._a9zs, span._aacl, span.x193iq5w');
                            if (pinTextEl) {
                                var pt = (pinTextEl.innerText || pinTextEl.textContent || '').trim();
                                if (pt.length >= $MIN_COMMENT_LENGTH && !isCaption(pt)) return pt;
                            }
                        }
                    }

                    // 3. Search structured comment rows
                    var containers = ${jsArray(COMMENT_CONTAINER_SELECTORS)};
                    var texts = ${jsArray(COMMENT_TEXT_SELECTORS)};
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

                    // 4. Fallback: all list items in article or dialog
                    var allLis = document.querySelectorAll('article ul li, div[role="dialog"] ul li, ul li');
                    for (var k = 0; k < allLis.length; k++) {
                        var span = allLis[k].querySelector('span[dir="auto"], span._a9zs, span._aacl');
                        if (span) {
                            var st = (span.innerText || span.textContent || '').trim();
                            if (st.length >= $MIN_COMMENT_LENGTH && !isCaption(st)) return st;
                        }
                    }
                } catch (e) {}
                return null;
            })();
        """.trimIndent()
    }
}
