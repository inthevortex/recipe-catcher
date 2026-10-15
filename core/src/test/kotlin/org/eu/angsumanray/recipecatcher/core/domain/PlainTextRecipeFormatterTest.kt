package org.eu.angsumanray.recipecatcher.core.domain

import org.eu.angsumanray.recipecatcher.core.model.Ingredient
import org.eu.angsumanray.recipecatcher.core.model.Recipe
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.core.model.Step
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlainTextRecipeFormatterTest {

    @Test
    fun `formats recipe into clean plain text without markdown syntax`() {
        val recipe = Recipe(
            id = 1L,
            sourceType = SourceType.WEB,
            sourceKey = "https://example.com/pancakes",
            sourceUrl = "https://example.com/pancakes",
            title = "Fluffy Buttermilk Pancakes",
            description = "The ultimate weekend breakfast treat.",
            servings = 4,
            prepMin = 10,
            cookMin = 15,
            cuisine = "American",
            ingredients = listOf(
                Ingredient(name = "Flour", baseName = "flour", category = "baking", quantity = 2.0, unit = "cups", position = 0),
                Ingredient(name = "Baking powder", baseName = "baking powder", category = "baking", quantity = 2.0, unit = "tsp", position = 1),
                Ingredient(name = "Salt", baseName = "salt", category = "spices", quantity = 0.5, unit = "tsp", position = 2),
                Ingredient(name = "Buttermilk", baseName = "buttermilk", category = "dairy", quantity = 1.5, unit = "cups", position = 3),
                Ingredient(name = "Egg", baseName = "egg", category = "dairy", quantity = 1.0, position = 4)
            ),
            steps = listOf(
                Step(position = 0, text = "Whisk dry ingredients together in a large bowl."),
                Step(position = 1, text = "Stir in buttermilk and egg until just combined."),
                Step(position = 2, text = "Cook on a hot griddle until bubbles pop, flip and finish.")
            ),
            personalNote = "Cook on medium-low heat so they do not burn."
        )

        val output = PlainTextRecipeFormatter.format(recipe)

        // Title and header
        assertTrue(output.contains("Fluffy Buttermilk Pancakes"))
        assertTrue(output.contains("========"))

        // Yield and times
        assertTrue(output.contains("Servings: 4"))
        assertTrue(output.contains("Prep: 10m"))
        assertTrue(output.contains("Cook: 15m"))
        assertTrue(output.contains("Cuisine: American"))

        // Ingredients
        assertTrue(output.contains("Ingredients:"))
        assertTrue(output.contains("  - 2 cups Flour"))
        assertTrue(output.contains("  - 2 tsp Baking powder"))
        assertTrue(output.contains("  - ½ tsp Salt"))
        assertTrue(output.contains("  - 1.5 cups Buttermilk"))
        assertTrue(output.contains("  - 1 Egg"))

        // Instructions
        assertTrue(output.contains("Instructions:"))
        assertTrue(output.contains("  1. Whisk dry ingredients together in a large bowl."))
        assertTrue(output.contains("  2. Stir in buttermilk and egg until just combined."))
        assertTrue(output.contains("  3. Cook on a hot griddle until bubbles pop, flip and finish."))

        // Notes and source
        assertTrue(output.contains("Notes:"))
        assertTrue(output.contains("  Cook on medium-low heat so they do not burn."))
        assertTrue(output.contains("Source: https://example.com/pancakes"))

        // Verify zero markdown formatting syntax
        assertFalse(output.contains("#"))
        assertFalse(output.contains("**"))
        assertFalse(output.contains("```"))
    }

    @Test
    fun `formats grouped ingredients correctly`() {
        val recipe = Recipe(
            id = 2L,
            sourceType = SourceType.INSTAGRAM,
            sourceKey = "post_123",
            title = "Enchiladas",
            ingredients = listOf(
                Ingredient(name = "Chicken breast", baseName = "chicken breast", category = "meat", quantity = 500.0, unit = "g", groupName = "Filling", position = 0),
                Ingredient(name = "Cumin", baseName = "cumin", category = "spices", quantity = 1.0, unit = "tsp", groupName = "Filling", position = 1),
                Ingredient(name = "Tortillas", baseName = "tortillas", category = "bakery", quantity = 8.0, unit = "pcs", groupName = "Assembly", position = 2),
                Ingredient(name = "Cheddar", baseName = "cheddar", category = "dairy", quantity = 200.0, unit = "g", groupName = "Assembly", position = 3)
            ),
            steps = listOf(
                Step(position = 0, text = "Cook filling."),
                Step(position = 1, text = "Roll into tortillas and bake.")
            )
        )

        val output = PlainTextRecipeFormatter.format(recipe)
        assertTrue(output.contains("  Filling:"))
        assertTrue(output.contains("    - 500 g Chicken breast"))
        assertTrue(output.contains("    - 1 tsp Cumin"))
        assertTrue(output.contains("  Assembly:"))
        assertTrue(output.contains("    - 8 pcs Tortillas"))
        assertTrue(output.contains("    - 200 g Cheddar"))
    }
}
