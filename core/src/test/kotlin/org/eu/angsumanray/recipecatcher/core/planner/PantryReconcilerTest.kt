package org.eu.angsumanray.recipecatcher.core.planner

import org.eu.angsumanray.recipecatcher.core.model.Ingredient
import org.eu.angsumanray.recipecatcher.core.model.InventoryItem
import org.eu.angsumanray.recipecatcher.core.model.InventoryStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PantryReconcilerTest {

    @Test
    fun `matches recipe ingredients against inventory by base name`() {
        val ingredients = listOf(
            Ingredient(name = "Yellow Onion", baseName = "onion", category = "produce"),
            Ingredient(name = "Diced Onions", baseName = "onion", category = "produce"), // Duplicate base name
            Ingredient(name = "Garlic Cloves", baseName = "garlic", category = "produce"),
            Ingredient(name = "Extra Virgin Olive Oil", baseName = "olive oil", category = "oils"),
            Ingredient(name = "Fresh Rosemary", baseName = "rosemary", category = "herbs")
        )

        val inventory = listOf(
            InventoryItem(id = 1L, name = "Onion", baseName = "onion", category = "produce", status = "in_stock"),
            InventoryItem(id = 2L, name = "Garlic", baseName = "garlic", category = "produce", status = "low"),
            InventoryItem(id = 3L, name = "Butter", baseName = "butter", category = "dairy", status = "in_stock")
        )

        val reconciled = PantryReconciler.matchIngredients(ingredients, inventory)

        // Duplicate 'onion' deduplicated: 4 distinct items
        assertEquals(4, reconciled.size)

        // Onion: matched with id 1, in_stock
        val onion = reconciled.first { it.baseName == "onion" }
        assertNotNull(onion.matchedInventoryItem)
        assertEquals(1L, onion.matchedInventoryItem?.id)
        assertEquals(InventoryStatus.IN_STOCK, onion.initialStatus)
        assertEquals(PantryAction.DONT_CHANGE, onion.action)

        // Garlic: matched with id 2, low
        val garlic = reconciled.first { it.baseName == "garlic" }
        assertNotNull(garlic.matchedInventoryItem)
        assertEquals(2L, garlic.matchedInventoryItem?.id)
        assertEquals(InventoryStatus.LOW, garlic.initialStatus)

        // Olive oil: not in inventory
        val oliveOil = reconciled.first { it.baseName == "olive oil" }
        assertNull(oliveOil.matchedInventoryItem)
        assertNull(oliveOil.initialStatus)
        assertEquals(PantryAction.DONT_CHANGE, oliveOil.action)

        // Rosemary: not in inventory
        val rosemary = reconciled.first { it.baseName == "rosemary" }
        assertNull(rosemary.matchedInventoryItem)
        assertNull(rosemary.initialStatus)
    }
}
