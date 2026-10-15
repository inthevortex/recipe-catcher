package org.eu.angsumanray.recipecatcher.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueryEngineTest {

    private val library = (1L..3000L).map { id ->
        when {
            id % 3 == 0L -> LibraryRecipe(id, "Paneer Tikka $id", ingredients = listOf("paneer", "yogurt", "chilli"), tags = listOf(TagRef("tag", "indian")), dietType = "vegetarian", totalMinutes = 30)
            id % 3 == 1L -> LibraryRecipe(id, "Thai Curry $id", ingredients = listOf("coconut milk", "tofu"), tags = listOf(TagRef("tag", "thai"), TagRef("diet", "soy")), dietType = "vegan", totalMinutes = 25)
            else -> LibraryRecipe(id, "Roast Chicken $id", ingredients = listOf("chicken", "butter"), complexity = "hard", totalMinutes = 90)
        }
    }

    @Test
    fun ingredientMatchesSpanTheWholeLibraryAndReportWhatMatched() {
        val matches = LibraryQueryEngine.find(RecipeQuery(ingredientsAny = listOf("Milk", "butter")), library)
        assertEquals(2000, matches.size)
        assertEquals(listOf("coconut milk"), matches.first().matchedIngredients)
    }

    @Test
    fun criteriaCombine() {
        val query = RecipeQuery(
            ingredientsNone = listOf("butter"),
            lacksTags = listOf(TagRef("contains", "soy")),
            dietTypes = listOf("vegetarian"),
            maxTotalMinutes = 45,
            text = "tikka"
        )
        val matches = LibraryQueryEngine.find(query, library)
        assertEquals(1000, matches.size)
        assertTrue(matches.all { it.recipe.title.startsWith("Paneer Tikka") })

        assertEquals(1000, LibraryQueryEngine.find(RecipeQuery(dietTypes = listOf("unknown"), complexity = "HARD"), library).size)
        assertEquals(1000, LibraryQueryEngine.find(RecipeQuery(hasTags = listOf(TagRef("tag", "Thai"))), library).size)
    }

    @Test
    fun describePagesLongResults() {
        val matches = LibraryQueryEngine.find(RecipeQuery(), library)
        val first = LibraryQueryEngine.describe(matches, offset = 0)
        assertTrue(first.startsWith("3000 recipes match; showing 1-300; call again with offset 300 for more."))
        assertEquals(LibraryQueryEngine.PAGE_SIZE + 1, first.lines().size)

        val last = LibraryQueryEngine.describe(matches, offset = 2900)
        assertTrue(last.startsWith("3000 recipes match; showing 2901-3000."))
        assertEquals("0 recipes match.", LibraryQueryEngine.describe(emptyList(), offset = 0))
    }
}
