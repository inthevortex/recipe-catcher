package org.eu.angsumanray.recipecatcher.capture

import org.eu.angsumanray.recipecatcher.core.insta.InstagramComments

/**
 * Scripts and DOM selectors for reading Instagram comments inside a WebView that has the saved
 * login cookies.
 *
 * Comments are read first through Instagram's own comments endpoint ([buildCommentsApiJs]), which
 * returns structured JSON. The rendered DOM ([buildExtractionJs]) is the fallback, generated from
 * the selector lists below, so they are the only place to change when Instagram's markup moves.
 *
 * To refresh the selectors:
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

    /** Buttons that open the comment list: text links on posts, the speech-bubble icon on reels. */
    val COMMENT_OPENER_SELECTORS = listOf(
        "svg[aria-label='Comment']",
        "svg[aria-label='Comments']"
    )

    /** Mobile web App ID, used when the page doesn't expose its own. */
    private const val FALLBACK_IG_APP_ID = "1217981644879628"

    /** Comment pages read, and reply threads opened, when looking for a specific comment and otherwise. */
    private const val MAX_TARGET_PAGES = 10
    private const val MAX_UNTARGETED_PAGES = 5
    private const val MAX_TARGET_THREADS = 20
    private const val MAX_UNTARGETED_THREADS = 5

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
     * Instagram's stylesheet turns off text selection everywhere; this re-enables it so the user can
     * long-press and select a comment in the in-app viewer.
     */
    const val SELECTABLE_TEXT_CSS =
        "* { -webkit-user-select: text !important; user-select: text !important; }"

    /** Defines `rcHeaders()`: the headers Instagram's web client sends with its own API requests. */
    private val REQUEST_HEADERS_JS = """
        function rcHeaders() {
            var m = document.cookie.match(/(?:^|; )csrftoken=([^;]*)/);
            var html = document.documentElement.innerHTML;
            var app = html.match(/"X-IG-App-ID":"(\d+)"/) || html.match(/"appId":"(\d+)"/);
            return {
                'Accept': 'application/json',
                'X-IG-App-ID': app ? app[1] : '$FALLBACK_IG_APP_ID',
                'X-CSRFToken': m ? decodeURIComponent(m[1]) : '',
                'X-Requested-With': 'XMLHttpRequest'
            };
        }
    """.trimIndent()

    /** Reads the state [buildMediaInfoJs] left behind, as a JSON string, or null before it starts. */
    const val READ_MEDIA_INFO_JS =
        "(function() { return window.__rcMedia ? JSON.stringify(window.__rcMedia) : null; })();"

    /**
     * Starts looking up the cover image of [mediaId] through Instagram's media info endpoint,
     * falling back to the page's `og:image`. The outcome lands in `window.__rcMedia` as
     * `{state: 'pending'|'done', url?, error?}`.
     */
    fun buildMediaInfoJs(mediaId: String): String = """
        (function() {
            if (window.__rcMedia) return;
            window.__rcMedia = { state: 'pending' };
            $REQUEST_HEADERS_JS

            function ogImage() {
                var meta = document.querySelector('meta[property="og:image"]');
                return meta ? meta.getAttribute('content') : null;
            }

            fetch('/api/v1/media/$mediaId/info/', { credentials: 'include', headers: rcHeaders() })
                .then(function(res) {
                    if (!res.ok) throw new Error('HTTP ' + res.status);
                    return res.json();
                })
                .then(function(body) {
                    var item = (body.items || [])[0] || {};
                    var media = (item.carousel_media && item.carousel_media[0]) || item;
                    var candidates = (media.image_versions2 && media.image_versions2.candidates) || [];
                    window.__rcMedia = { state: 'done', url: candidates.length ? candidates[0].url : ogImage() };
                })
                .catch(function(e) {
                    window.__rcMedia = { state: 'done', url: ogImage(), error: String(e) };
                });
        })();
    """.trimIndent()

    /** Reads the state [buildCommentsApiJs] left behind, as a JSON string, or null before it starts. */
    const val READ_COMMENTS_API_JS =
        "(function() { return window.__rcApi ? JSON.stringify(window.__rcApi) : null; })();"

    /**
     * Starts fetching the comments of [mediaId] from the page's own origin, with the session cookies.
     * The outcome lands in `window.__rcApi` as `{state: 'pending'|'done'|'error', body?, error?, notes?}`,
     * where `body` is the endpoint's JSON with every fetched page's comments merged and each opened
     * reply thread under its parent's `child_comments`, and `notes` summarises the requests made.
     * Integers too large for a JS number (comment and user ids) are kept as strings.
     */
    fun buildCommentsApiJs(mediaId: String, targetCommentId: String? = null): String {
        val targetJs = jsString(targetCommentId)
        val maxPages = if (targetCommentId.isNullOrBlank()) MAX_UNTARGETED_PAGES else MAX_TARGET_PAGES
        val maxThreads = if (targetCommentId.isNullOrBlank()) MAX_UNTARGETED_THREADS else MAX_TARGET_THREADS
        return """
            (function() {
                if (window.__rcApi) return;
                window.__rcApi = { state: 'pending' };
                var mediaId = '$mediaId';
                var target = $targetJs;

                $REQUEST_HEADERS_JS

                // Ids exceed 2^53, so JSON.parse would round them; quote long integers first.
                function parseBody(text) {
                    return JSON.parse(text.replace(/([:\[,]\s*)(-?\d{16,})(?=\s*[,\]}])/g, '$1"$2"'));
                }

                function isTarget(c) { return target && String(c.pk) === target; }

                async function run() {
                    var notes = [];
                    var headers = rcHeaders();
                    var merged = null;
                    var cursor = '';
                    for (var page = 0; page < $maxPages; page++) {
                        var url = '/api/v1/media/' + mediaId + '/comments/?can_support_threading=true&permalink_enabled=false' + cursor;
                        var res = await fetch(url, { credentials: 'include', headers: headers });
                        if (!res.ok) throw new Error('HTTP ' + res.status);
                        var body = parseBody(await res.text());
                        var comments = body.comments || [];
                        notes.push('page' + page + ':' + comments.length +
                                   (body.comment_count != null ? '/' + body.comment_count : '') +
                                   (body.has_more_comments ? ',more' : '') +
                                   (body.has_more_headload_comments ? ',head' : '') +
                                   (body.next_max_id ? ',max' : '') +
                                   (body.next_min_id ? ',min' : ''));
                        if (!merged) {
                            merged = body;
                            merged.comments = comments.slice();
                        } else {
                            merged.comments = merged.comments.concat(comments);
                        }
                        if (comments.some(isTarget)) break;
                        // Older comments page with max_id, newer ones with min_id.
                        if (body.has_more_comments && body.next_max_id) {
                            cursor = '&max_id=' + encodeURIComponent(body.next_max_id);
                        } else if ((body.has_more_headload_comments || body.has_more_comments) && body.next_min_id) {
                            cursor = '&min_id=' + encodeURIComponent(body.next_min_id);
                        } else {
                            break;
                        }
                    }

                    // The recipe is often the author's reply to a "recipe?" comment.
                    if (!merged.comments.some(isTarget)) {
                        var parents = merged.comments.filter(function(c) { return (c.child_comment_count || 0) > 0; })
                                                     .slice(0, $maxThreads);
                        for (var t = 0; t < parents.length; t++) {
                            try {
                                var threadUrl = '/api/v1/media/' + mediaId + '/comments/' + parents[t].pk + '/child_comments/';
                                var threadRes = await fetch(threadUrl, { credentials: 'include', headers: headers });
                                notes.push('thread' + t + ':' + threadRes.status);
                                if (!threadRes.ok) continue;
                                var thread = parseBody(await threadRes.text());
                                parents[t].child_comments = thread.child_comments || [];
                                notes.push('replies' + t + ':' + parents[t].child_comments.length + '/' + parents[t].child_comment_count);
                                if (parents[t].child_comments.some(isTarget)) break;
                            } catch (e) {
                                notes.push('thread' + t + ':' + e);
                            }
                        }
                    }
                    window.__rcApi = { state: 'done', body: JSON.stringify(merged), notes: notes.join(' ') };
                }

                run().catch(function(e) {
                    window.__rcApi = { state: 'error', error: String(e) };
                });
            })();
        """.trimIndent()
    }

    /**
     * Reads rendered comment rows. Opens the comment list once (the drawer on reels), then returns
     * a JSON string `{target, pinned, texts}`: the targeted comment's text, a pinned comment's
     * text, and every other comment-like text in page order. Usernames, which sit in links next to
     * the comment, are never returned.
     */
    fun buildExtractionJs(targetCommentId: String? = null, knownCaption: String? = null): String {
        fun jsArray(items: List<String>) = items.joinToString(prefix = "[", postfix = "]") { jsString(it) }
        val knownCapJs = jsString(knownCaption?.replace("\n", " ")?.replace("\r", ""))

        return """
            (function() {
                var result = { target: null, pinned: null, texts: [] };
                try {
                    var targetCommentId = ${jsString(targetCommentId)};
                    var knownCaption = $knownCapJs;
                    var textSelector = ${jsString(COMMENT_TEXT_SELECTORS.joinToString(", "))};

                    if (!window.__rcOpened) {
                        window.__rcOpened = true;
                        var opened = false;
                        var openers = ${jsArray(COMMENT_OPENER_SELECTORS)};
                        for (var o = 0; o < openers.length && !opened; o++) {
                            var icon = document.querySelector(openers[o]);
                            var button = icon && (icon.closest('button, [role="button"], a') || icon.parentElement);
                            if (button) { button.click(); opened = true; }
                        }
                        if (!opened) {
                            var clickable = document.querySelectorAll('button, div[role="button"], a, span');
                            for (var b = 0; b < clickable.length; b++) {
                                var txt = (clickable[b].innerText || clickable[b].textContent || '').toLowerCase().trim();
                                if (txt.length < 40 && txt.indexOf('view') >= 0 && txt.indexOf('comment') >= 0) {
                                    clickable[b].click();
                                    break;
                                }
                            }
                        }
                    }

                    function normalise(t) { return (t || '').replace(/\s+/g, ' ').trim(); }

                    var captions = [];
                    if (knownCaption) captions.push(normalise(knownCaption));
                    var caps = document.querySelectorAll(${jsString(CAPTION_SELECTORS.joinToString(", "))});
                    for (var c = 0; c < caps.length; c++) {
                        var capText = normalise(caps[c].innerText || caps[c].textContent);
                        if (capText) captions.push(capText);
                    }

                    function isCaption(t) {
                        var n = normalise(t);
                        for (var i = 0; i < captions.length; i++) {
                            if (n === captions[i] || captions[i].indexOf(n) >= 0 || n.indexOf(captions[i]) >= 0) return true;
                        }
                        return false;
                    }

                    function looksLikeComment(t) {
                        return t.length >= ${InstagramComments.MIN_COMMENT_LENGTH} && /\s/.test(t) && !isCaption(t);
                    }

                    // Longest comment-like text in a row, skipping the author's username link.
                    function rowText(row) {
                        var best = '';
                        var els = row.querySelectorAll(textSelector);
                        for (var i = 0; i < els.length; i++) {
                            if (els[i].closest('a, h2, h3')) continue;
                            var t = (els[i].innerText || els[i].textContent || '').trim();
                            if (looksLikeComment(t) && t.length > best.length) best = t;
                        }
                        return best;
                    }

                    if (targetCommentId) {
                        var targetEl = document.querySelector('[data-comment-id="' + targetCommentId + '"]') ||
                                       document.querySelector('a[href*="' + targetCommentId + '"]');
                        if (targetEl) {
                            var row = targetEl.closest('li') || targetEl.closest('div.x1lliihq') || targetEl.closest('div[role="presentation"]');
                            if (row) result.target = rowText(row) || null;
                        }
                    }

                    var pins = document.querySelectorAll('svg[aria-label*="Pin"], svg[aria-label*="pin"], [aria-label*="Pinned"], [aria-label*="pinned"]');
                    for (var p = 0; p < pins.length && !result.pinned; p++) {
                        var pinRow = pins[p].closest('li') || pins[p].closest('div.x1lliihq');
                        if (pinRow) result.pinned = rowText(pinRow) || null;
                    }

                    var containers = ${jsArray(COMMENT_CONTAINER_SELECTORS)};
                    for (var k = 0; k < containers.length; k++) {
                        var rows = document.querySelectorAll(containers[k]);
                        for (var r = 0; r < rows.length; r++) {
                            var text = rowText(rows[r]);
                            if (text && result.texts.indexOf(text) < 0) result.texts.push(text);
                        }
                    }
                } catch (e) {}
                return JSON.stringify(result);
            })();
        """.trimIndent()
    }

    private fun jsString(value: String?): String {
        if (value == null) return "null"
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")
        return "'$escaped'"
    }
}
