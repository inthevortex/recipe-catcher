package org.eu.angsumanray.recipecatcher.core.insta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstagramCommentsTest {

    private val recipe = "Ingredients:\n2 cups rice\n1 tbsp ghee\n3 cloves garlic\nMethod: fry the garlic, add rice and simmer."

    private fun response(vararg comments: String, captionUser: String = """{"pk":"111","username":"chef"}""") = """
        {
          "caption": {"pk":"1","text":"Recipe in comments!","user_id":111,"user":$captionUser},
          "comments": [${comments.joinToString(",")}],
          "has_more_comments": false,
          "status": "ok"
        }
    """.trimIndent()

    private fun comment(pk: String, text: String, userPk: String = "222", username: String = "fan", extra: String = "") =
        """{"pk":"$pk","text":${quote(text)},"user_id":$userPk,"user":{"pk":"$userPk","username":"$username"}$extra}"""

    private fun quote(text: String) = "\"" + text.replace("\n", "\\n") + "\""

    @Test
    fun `mediaIdFromShortcode decodes base64 shortcodes`() {
        assertEquals("3974353981845268366", InstagramComments.mediaIdFromShortcode("DcnveOzi-eO"))
        assertEquals("3974353981845268366", InstagramComments.mediaIdFromShortcode("DcnveOzi-eOxyzPRIVATE"))
        assertNull(InstagramComments.mediaIdFromShortcode("bad!code"))
        assertNull(InstagramComments.mediaIdFromShortcode(""))
    }

    @Test
    fun `looksLikeCommentText rejects lone usernames`() {
        assertFalse(InstagramComments.looksLikeCommentText("miles_and_meals01"))
        assertFalse(InstagramComments.looksLikeCommentText("so good"))
        assertTrue(InstagramComments.looksLikeCommentText("This looks so good, saving it"))
    }

    @Test
    fun `pickRecipeComment returns the targeted comment`() {
        val json = response(
            comment("10", "Wow amazing, I need to try this"),
            comment("18035225489906097", "short but targeted")
        )
        assertEquals("short but targeted", InstagramComments.pickRecipeComment(json, "18035225489906097"))
    }

    @Test
    fun `pickRecipeComment prefers the author's recipe comment over fan comments`() {
        val json = response(
            comment("10", "Recipe please!! This looks amazing and I want to bake it"),
            comment("11", recipe, userPk = "111", username = "chef"),
            comment("12", "Love this so much, thank you for sharing")
        )
        assertEquals(recipe, InstagramComments.pickRecipeComment(json))
    }

    @Test
    fun `pickRecipeComment finds a recipe reply by the author`() {
        val reply = comment("21", recipe, userPk = "111", username = "chef")
        val json = response(
            comment("20", "Where is the recipe? I can't find it anywhere", extra = """, "preview_child_comments":[$reply]""")
        )
        assertEquals(recipe, InstagramComments.pickRecipeComment(json))
    }

    @Test
    fun `pickRecipeComment falls back to any clearly recipe-like comment`() {
        val json = response(
            comment("10", "Gorgeous colours in this video honestly"),
            comment("11", recipe, userPk = "333", username = "helper")
        )
        assertEquals(recipe, InstagramComments.pickRecipeComment(json))
    }

    @Test
    fun `pickRecipeComment returns null when nothing looks like a recipe`() {
        val json = response(
            comment("10", "miles_and_meals01"),
            comment("11", "Gorgeous colours in this video honestly")
        )
        assertNull(InstagramComments.pickRecipeComment(json))
        assertNull(InstagramComments.pickRecipeComment("<html>login</html>"))
    }

    @Test
    fun `pickRecipeComment ignores a comment that repeats the caption`() {
        val json = response(comment("11", recipe, userPk = "111", username = "chef"))
        assertNull(InstagramComments.pickRecipeComment(json, knownCaption = recipe))
    }

    @Test
    fun `mostRecipeLike prefers recipe text and otherwise keeps page order`() {
        assertEquals(recipe, InstagramComments.mostRecipeLike(listOf("Looks lovely, saving this one", recipe)))
        assertEquals("Looks lovely, saving this one", InstagramComments.mostRecipeLike(listOf("Looks lovely, saving this one", "Another nice comment here")))
        assertNull(InstagramComments.mostRecipeLike(emptyList()))
    }
}
