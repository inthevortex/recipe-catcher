package org.eu.angsumanray.recipecatcher.ui.edit

import org.eu.angsumanray.recipecatcher.core.llm.ExtractedIngredient
import org.eu.angsumanray.recipecatcher.core.llm.ExtractedIngredientGroup
import org.eu.angsumanray.recipecatcher.core.llm.ExtractedRecipe
import org.eu.angsumanray.recipecatcher.core.llm.ExtractedStep
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.ui.edit.EditRecipeViewModel.Companion.formHasContent
import org.eu.angsumanray.recipecatcher.ui.edit.EditRecipeViewModel.Companion.mergeExtracted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindWithAiMergeTest {

    private val extracted = ExtractedRecipe(
        isRecipe = true,
        title = "Shorshe Ilish",
        description = "Hilsa in mustard",
        servings = 3,
        prepMinutes = 10,
        cookMinutes = 20,
        totalMinutes = 30,
        cuisine = "Bengali",
        complexity = "medium",
        complexityReason = "Grinding mustard",
        spiceLevel = "medium",
        dietType = "non-veg",
        contains = emptyList(),
        kcalPerServing = 350,
        makeAhead = false,
        freezerFriendly = false,
        ingredientGroups = listOf(
            ExtractedIngredientGroup(
                name = null,
                items = listOf(ExtractedIngredient(2.0, null, "tbsp", "mustard seeds", "mustard", "spices", null, "2 tbsp mustard seeds"))
            )
        ),
        steps = listOf(ExtractedStep("Grind the mustard.", null)),
        equipment = emptyList(),
        consumables = emptyList(),
        tags = emptyList(),
        newListSuggestions = emptyList(),
        confidence = 0.9
    )

    private val blankForm = EditRecipeUiState(
        isLoading = false,
        ingredients = listOf(IngredientEntity(recipeId = 0, position = 0, name = "", baseName = "", category = "pantry", rawText = "")),
        quantityTexts = listOf(""),
        steps = listOf(StepEntity(recipeId = 0, position = 0, text = ""))
    )

    private val userForm = blankForm.copy(
        title = "Mum's ilish",
        cuisine = "",
        cookMin = 25,
        servingsText = "2",
        ingredients = listOf(IngredientEntity(recipeId = 0, position = 0, name = "hilsa", baseName = "hilsa", category = "meat & fish", rawText = "hilsa")),
        quantityTexts = listOf("4"),
        selectedTagIds = setOf(7L)
    )

    @Test
    fun `blank form counts as empty and gets everything`() {
        assertFalse(formHasContent(blankForm.copy(title = "Ilish")))

        val merged = mergeExtracted(blankForm, extracted, setOf(3L), "https://site.com/ilish", "Fallback", replaceExisting = false)

        assertEquals("Shorshe Ilish", merged.title)
        assertEquals("3", merged.servingsText)
        assertEquals(listOf("mustard seeds"), merged.ingredients.map { it.name })
        assertEquals(listOf("2"), merged.quantityTexts)
        assertEquals(listOf("Grind the mustard."), merged.steps.map { it.text })
        assertEquals(setOf(3L), merged.selectedTagIds)
        assertEquals("https://site.com/ilish", merged.captionRaw)
    }

    @Test
    fun `fill empty keeps user typed fields, ingredients and tags`() {
        assertTrue(formHasContent(userForm))

        val merged = mergeExtracted(userForm, extracted, setOf(3L), "https://site.com/ilish", "Fallback", replaceExisting = false)

        assertEquals("Mum's ilish", merged.title)
        assertEquals(25, merged.cookMin)
        assertEquals("2", merged.servingsText)
        assertEquals(listOf("hilsa"), merged.ingredients.map { it.name })
        assertEquals(listOf("4"), merged.quantityTexts)
        assertEquals("Bengali", merged.cuisine)
        assertEquals(10, merged.prepMin)
        assertEquals(listOf("Grind the mustard."), merged.steps.map { it.text })
        assertEquals(setOf(7L, 3L), merged.selectedTagIds)
    }

    @Test
    fun `replace overwrites with the found recipe`() {
        val merged = mergeExtracted(userForm, extracted, setOf(3L), "https://site.com/ilish", "Fallback", replaceExisting = true)

        assertEquals("Shorshe Ilish", merged.title)
        assertEquals(20, merged.cookMin)
        assertEquals("3", merged.servingsText)
        assertEquals(listOf("mustard seeds"), merged.ingredients.map { it.name })
        assertEquals(setOf(3L), merged.selectedTagIds)
    }
}
