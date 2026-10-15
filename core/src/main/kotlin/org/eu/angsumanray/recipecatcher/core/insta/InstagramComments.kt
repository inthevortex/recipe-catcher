package org.eu.angsumanray.recipecatcher.core.insta

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.math.BigInteger

/**
 * Picks the recipe comment out of the JSON returned by Instagram's web comments endpoint
 * (`/api/v1/media/<media id>/comments/`).
 */
object InstagramComments {

    /** Shorter texts are usually emoji reactions, tags or usernames. */
    const val MIN_COMMENT_LENGTH = 15

    private const val SHORTCODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Instagram appends extra characters to private-post shortcodes; only the first 11 encode the id. */
    private const val MEDIA_ID_SHORTCODE_LENGTH = 11

    private val RECIPE_KEYWORDS = Regex(
        """\b(ingredients?|recipe|method|instructions?|directions|steps?|preheat|bake|simmer|marinate|serves|servings)\b""",
        RegexOption.IGNORE_CASE
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Comment(
        val id: String,
        val text: String,
        val userId: String?,
        val username: String?,
        val isPinned: Boolean
    )

    /** The numeric media id a post shortcode encodes, or null for a malformed shortcode. */
    fun mediaIdFromShortcode(shortcode: String): String? {
        val code = shortcode.take(MEDIA_ID_SHORTCODE_LENGTH)
        if (code.isEmpty()) return null
        var id = BigInteger.ZERO
        val base = BigInteger.valueOf(64)
        for (c in code) {
            val digit = SHORTCODE_ALPHABET.indexOf(c)
            if (digit < 0) return null
            id = id.multiply(base).add(BigInteger.valueOf(digit.toLong()))
        }
        return id.toString()
    }

    /** True when [text] reads like a sentence rather than a lone username, tag or emoji run. */
    fun looksLikeCommentText(text: String?): Boolean {
        val trimmed = text?.trim().orEmpty()
        return trimmed.length >= MIN_COMMENT_LENGTH && trimmed.any { it.isWhitespace() }
    }

    /** All comments in [responseJson], top-level comments first, then their replies. */
    fun parse(responseJson: String): List<Comment> = parseRoot(responseJson)?.let(::comments).orEmpty()

    private fun comments(root: JsonObject): List<Comment> {
        val topLevel = (root["comments"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val replies = topLevel.flatMap {
            (it["child_comments"] as? JsonArray).orEmpty() + (it["preview_child_comments"] as? JsonArray).orEmpty()
        }.mapNotNull { it as? JsonObject }
        return (topLevel + replies).mapNotNull(::toComment)
    }

    /**
     * The comment most likely to hold the recipe: the one [targetCommentId] names when present,
     * otherwise a recipe-like comment by the post's author, otherwise any clearly recipe-like comment.
     * Returns null when no comment qualifies.
     */
    fun pickRecipeComment(
        responseJson: String,
        targetCommentId: String? = null,
        knownCaption: String? = null
    ): String? {
        val root = parseRoot(responseJson) ?: return null
        val comments = comments(root)
        if (!targetCommentId.isNullOrBlank()) {
            comments.firstOrNull { it.id == targetCommentId && it.text.isNotBlank() }?.let { return it.text }
        }

        val owner = ownerOf(root)
        val caption = knownCaption?.trim().orEmpty()
        val candidates = comments
            .filter { looksLikeCommentText(it.text) }
            .filter { caption.isEmpty() || it.text.trim() != caption }
            .distinctBy { it.id }

        fun Comment.isByOwner() = owner != null &&
            ((owner.first != null && userId == owner.first) || (owner.second != null && username == owner.second))

        val byOwner = candidates.filter { it.isByOwner() }
        byOwner.filter { recipeScore(it.text) >= 2 }.maxByOrNull { recipeScore(it.text) }?.let { return it.text }
        candidates.filter { recipeScore(it.text) >= 3 }
            .maxWithOrNull(compareBy<Comment> { recipeScore(it.text) }.thenBy { it.text.length })
            ?.let { return it.text }
        candidates.firstOrNull { it.isPinned && recipeScore(it.text) >= 1 }?.let { return it.text }
        return byOwner.filter { it.text.length >= 80 }.maxByOrNull { it.text.length }?.text
    }

    /** The most recipe-like of [texts], or the first one when none reads like a recipe. */
    fun mostRecipeLike(texts: List<String>): String? {
        val best = texts.maxByOrNull { recipeScore(it) } ?: return null
        return if (recipeScore(best) > 0) best else texts.first()
    }

    /** Rough measure of how much [text] reads like a recipe. */
    fun recipeScore(text: String): Int {
        val quantityLines = InstagramPostFetcher.quantityLineCount(text)
        val keywords = RECIPE_KEYWORDS.findAll(text).count().coerceAtMost(3)
        return quantityLines + keywords
    }

    private fun toComment(obj: JsonObject): Comment? {
        val id = obj.string("pk") ?: obj.string("id") ?: return null
        val text = obj.string("text")?.trim().orEmpty()
        val user = obj["user"] as? JsonObject
        return Comment(
            id = id,
            text = text,
            userId = obj.string("user_id") ?: user?.string("pk") ?: user?.string("id"),
            username = user?.string("username"),
            isPinned = (obj["is_pinned"] as? JsonPrimitive)?.booleanOrNull == true
        )
    }

    /** The post author's (user id, username), taken from the caption the endpoint returns alongside. */
    private fun ownerOf(root: JsonObject): Pair<String?, String?>? {
        val caption = root["caption"] as? JsonObject ?: return null
        val user = caption["user"] as? JsonObject
        val userId = caption.string("user_id") ?: user?.string("pk") ?: user?.string("id")
        val username = user?.string("username")
        return if (userId == null && username == null) null else userId to username
    }

    private fun parseRoot(responseJson: String): JsonObject? = try {
        json.parseToJsonElement(responseJson) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
}
