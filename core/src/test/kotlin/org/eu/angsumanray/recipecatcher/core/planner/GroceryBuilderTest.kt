package org.eu.angsumanray.recipecatcher.core.planner

import org.eu.angsumanray.recipecatcher.core.model.GroceryItem
import org.eu.angsumanray.recipecatcher.core.model.GrocerySource
import org.eu.angsumanray.recipecatcher.core.model.Ingredient
import org.eu.angsumanray.recipecatcher.core.model.InventoryItem
import org.eu.angsumanray.recipecatcher.core.model.InventoryStatus
import org.eu.angsumanray.recipecatcher.core.model.PlanEntry
import org.eu.angsumanray.recipecatcher.core.model.PlanEntryKind
import org.eu.angsumanray.recipecatcher.core.model.Recipe
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroceryBuilderTest {

    private fun sampleRecipe(
        id: Long,
        title: String,
        servings: Int,
        ingredients: List<Ingredient>
    ): Recipe {
        return Recipe(
            id = id,
            sourceType = SourceType.MANUAL,
            sourceKey = "recipe-$id",
            title = title,
            servings = servings,
            ingredients = ingredients
        )
    }

    @Test
    fun `buildFromPlan aggregates and scales ingredients across multiple meals`() {
        // Recipe 1: 2 servings. Needs 200g paneer, 2 tomatoes.
        val r1 = sampleRecipe(
            id = 1,
            title = "Paneer Tikka",
            servings = 2,
            ingredients = listOf(
                Ingredient(name = "Paneer cubes", baseName = "paneer", category = "dairy", quantity = 200.0, unit = "g"),
                Ingredient(name = "Ripe tomatoes", baseName = "tomato", category = "vegetables", quantity = 2.0, unit = "pc")
            )
        )

        // Recipe 2: 4 servings. Needs 300g paneer, 1 tomato.
        val r2 = sampleRecipe(
            id = 2,
            title = "Paneer Butter Masala",
            servings = 4,
            ingredients = listOf(
                Ingredient(name = "Paneer blocks", baseName = "paneer", category = "dairy", quantity = 300.0, unit = "g"),
                Ingredient(name = "Tomatoes", baseName = "tomato", category = "vegetables", quantity = 1.0, unit = "pc")
            )
        )

        // Cook r1 with 4 portions (scale factor 4/2 = 2.0) -> paneer: 400g, tomato: 4 pc
        // Cook r2 with 4 portions (scale factor 4/4 = 1.0) -> paneer: 300g, tomato: 1 pc
        // Total expected: paneer = 700g, tomato = 5 pc
        val entries = listOf(
            PlanEntry(id = 1, slotId = 1, kind = PlanEntryKind.COOK.value, recipeId = 1, portions = 4),
            PlanEntry(id = 2, slotId = 2, kind = PlanEntryKind.COOK.value, recipeId = 2, portions = 4)
        )

        val items = GroceryBuilder.buildFromPlan(
            entries = entries,
            recipes = mapOf(1L to r1, 2L to r2)
        )

        val paneer = items.firstOrNull { it.baseName == "paneer" }
        assertNotNull(paneer)
        assertEquals("dairy", paneer.category)
        assertEquals("700 g", paneer.quantityText)

        val tomato = items.firstOrNull { it.baseName == "tomato" }
        assertNotNull(tomato)
        assertEquals("vegetables", tomato.category)
        assertEquals("5 pc", tomato.quantityText)
    }

    @Test
    fun `buildFromPlan converts kg and g into combined weight`() {
        val r1 = sampleRecipe(
            id = 1,
            title = "Flour bake",
            servings = 1,
            ingredients = listOf(
                Ingredient(name = "Flour", baseName = "flour", category = "grains & flours", quantity = 500.0, unit = "g")
            )
        )
        val r2 = sampleRecipe(
            id = 2,
            title = "Bread",
            servings = 1,
            ingredients = listOf(
                Ingredient(name = "Flour", baseName = "flour", category = "grains & flours", quantity = 1.0, unit = "kg")
            )
        )

        val entries = listOf(
            PlanEntry(id = 1, slotId = 1, kind = "cook", recipeId = 1, portions = 1),
            PlanEntry(id = 2, slotId = 2, kind = "cook", recipeId = 2, portions = 1)
        )

        val items = GroceryBuilder.buildFromPlan(entries, mapOf(1L to r1, 2L to r2))
        val flour = items.firstOrNull { it.baseName == "flour" }
        assertNotNull(flour)
        // 500g + 1000g = 1500g -> 1.5 kg
        assertEquals("1.5 kg", flour.quantityText)
    }

    @Test
    fun `buildFromPlan subtracts in-stock items, marks low, adds out`() {
        val recipe = sampleRecipe(
            id = 1,
            title = "Salad",
            servings = 1,
            ingredients = listOf(
                Ingredient(name = "Cucumber", baseName = "cucumber", category = "vegetables", quantity = 1.0, unit = "pc"),
                Ingredient(name = "Carrots", baseName = "carrot", category = "vegetables", quantity = 2.0, unit = "pc"),
                Ingredient(name = "Olive Oil", baseName = "olive oil", category = "pantry", quantity = 1.0, unit = "tbsp")
            )
        )

        val entries = listOf(
            PlanEntry(id = 1, slotId = 1, kind = "cook", recipeId = 1, portions = 1)
        )

        val inventory = listOf(
            InventoryItem(id = 10, name = "Cucumber", baseName = "cucumber", category = "vegetables", status = InventoryStatus.IN_STOCK.value),
            InventoryItem(id = 11, name = "Carrots", baseName = "carrot", category = "vegetables", status = InventoryStatus.LOW.value),
            InventoryItem(id = 12, name = "Eggs", baseName = "egg", category = "dairy", status = InventoryStatus.OUT.value)
        )

        val items = GroceryBuilder.buildFromPlan(entries, mapOf(1L to recipe), inventory = inventory)

        // Cucumber is in stock -> excluded
        assertFalse(items.any { it.baseName == "cucumber" })

        // Carrot is low -> included, marked running low
        val carrot = items.firstOrNull { it.baseName == "carrot" }
        assertNotNull(carrot)
        assertTrue(carrot.quantityText?.contains("running low") == true)

        // Olive oil has no inventory -> included normally
        val oil = items.firstOrNull { it.baseName == "olive oil" }
        assertNotNull(oil)

        // Egg is marked OUT in inventory -> added to grocery list with source = inventory
        val egg = items.firstOrNull { it.baseName == "egg" }
        assertNotNull(egg)
        assertEquals(GrocerySource.INVENTORY.value, egg.source)
    }

    @Test
    fun `buildFromPlan preserves manual items and checked state`() {
        val recipe = sampleRecipe(
            id = 1,
            title = "Rice bowl",
            servings = 1,
            ingredients = listOf(
                Ingredient(name = "Basmati rice", baseName = "rice", category = "grains & flours", quantity = 200.0, unit = "g")
            )
        )

        val entries = listOf(
            PlanEntry(id = 1, slotId = 1, kind = "cook", recipeId = 1, portions = 1)
        )

        val existing = listOf(
            GroceryItem(id = 50, name = "Rice", baseName = "rice", category = "grains & flours", source = "plan", checked = true),
            GroceryItem(id = 51, name = "Chocolate snack", baseName = "chocolate", category = "pantry", source = GrocerySource.MANUAL.value, checked = false)
        )

        val items = GroceryBuilder.buildFromPlan(entries, mapOf(1L to recipe), existingGroceryItems = existing)

        val rice = items.firstOrNull { it.baseName == "rice" }
        assertNotNull(rice)
        assertTrue(rice.checked)

        val choc = items.firstOrNull { it.baseName == "chocolate" }
        assertNotNull(choc)
        assertEquals(GrocerySource.MANUAL.value, choc.source)
    }
}
