package org.eu.angsumanray.recipecatcher.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.eu.angsumanray.recipecatcher.core.model.Ingredient
import org.eu.angsumanray.recipecatcher.core.model.InventoryItem
import org.eu.angsumanray.recipecatcher.core.planner.PantryAction
import org.eu.angsumanray.recipecatcher.core.planner.PantryReconciler
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.InventoryItemEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PantryReconciliationTest {

    private lateinit var context: Context
    private lateinit var database: RecipeDatabase
    private lateinit var listsRepository: ListsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        listsRepository = ListsRepository(
            database = database,
            listsDao = database.listsDao(),
            plannerDao = database.plannerDao(),
            recipeDao = database.recipeDao(),
            ingredientDao = database.ingredientDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `applyPantryReconciliation updates existing items and adds newly tracked items`() = runBlocking {
        // Pre-populate inventory
        val onionId = listsRepository.addInventoryItem(
            name = "Yellow Onion",
            baseName = "onion",
            category = "produce",
            status = InventoryItemEntity.STATUS_IN_STOCK
        )
        val garlicId = listsRepository.addInventoryItem(
            name = "Garlic",
            baseName = "garlic",
            category = "produce",
            status = InventoryItemEntity.STATUS_IN_STOCK
        )

        val inventoryEntities = listsRepository.getAllInventoryItems()
        val coreInventory = inventoryEntities.map {
            InventoryItem(
                id = it.id,
                name = it.name,
                baseName = it.baseName,
                category = it.category,
                status = it.status,
                updatedAt = it.updatedAt
            )
        }

        val recipeIngredients = listOf(
            Ingredient(name = "Yellow Onion", baseName = "onion", category = "produce"),
            Ingredient(name = "Garlic Cloves", baseName = "garlic", category = "produce"),
            Ingredient(name = "Dried Oregano", baseName = "oregano", category = "spices"),
            Ingredient(name = "Salt", baseName = "salt", category = "spices")
        )

        // Run reconciliation matching
        val items = PantryReconciler.matchIngredients(recipeIngredients, coreInventory)

        // Modify actions:
        // - Onion ran out: OUT
        // - Garlic running low: LOW
        // - Oregano newly tracked: IN_STOCK
        // - Salt unchanged: DONT_CHANGE
        val modifiedItems = items.map { item ->
            when (item.baseName) {
                "onion" -> item.copy(action = PantryAction.OUT)
                "garlic" -> item.copy(action = PantryAction.LOW)
                "oregano" -> item.copy(action = PantryAction.IN_STOCK)
                else -> item
            }
        }

        // Apply reconciliation to Room
        listsRepository.applyPantryReconciliation(modifiedItems)

        // Verify inventory results
        val updatedOnion = database.listsDao().getInventoryItemByBaseName("onion")
        assertNotNull(updatedOnion)
        assertEquals(InventoryItemEntity.STATUS_OUT, updatedOnion?.status)

        val updatedGarlic = database.listsDao().getInventoryItemByBaseName("garlic")
        assertNotNull(updatedGarlic)
        assertEquals(InventoryItemEntity.STATUS_LOW, updatedGarlic?.status)

        val newOregano = database.listsDao().getInventoryItemByBaseName("oregano")
        assertNotNull(newOregano)
        assertEquals(InventoryItemEntity.STATUS_IN_STOCK, newOregano?.status)
        assertEquals("Dried Oregano", newOregano?.name)

        // Salt had DONT_CHANGE and was not in inventory, so it should not exist
        val salt = database.listsDao().getInventoryItemByBaseName("salt")
        assertEquals(null, salt)

        // Since onion was marked OUT, it should also have been automatically added to grocery list
        val groceries = database.listsDao().getAllGroceryItems()
        assertTrue("Item marked OUT should be placed on grocery list", groceries.any { it.baseName == "onion" })
    }
}
