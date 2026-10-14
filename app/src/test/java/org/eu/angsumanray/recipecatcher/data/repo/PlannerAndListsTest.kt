package org.eu.angsumanray.recipecatcher.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.GroceryItemEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.InventoryItemEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanEntryEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanSlotEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlannerAndListsTest {

    private lateinit var database: RecipeDatabase
    private lateinit var plannerRepository: PlannerRepository
    private lateinit var listsRepository: ListsRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        plannerRepository = PlannerRepository(
            database = database,
            plannerDao = database.plannerDao(),
            cookLogDao = database.cookLogDao(),
            recipeDao = database.recipeDao()
        )

        listsRepository = ListsRepository(
            database = database,
            listsDao = database.listsDao(),
            plannerDao = database.plannerDao(),
            recipeDao = database.recipeDao(),
            ingredientDao = database.ingredientDao()
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `ensureDefaultSlotsForDate creates standard slots if empty`() = runBlocking {
        val date = LocalDate.of(2026, 10, 15)
        val slots = plannerRepository.ensureDefaultSlotsForDate(date)

        assertEquals(3, slots.size)
        assertEquals(listOf("Breakfast", "Lunch", "Dinner"), slots.map { it.name })
    }

    @Test
    fun `addCookEntry and toggleEntryDone writes cook log`() = runBlocking {
        val date = LocalDate.of(2026, 10, 15)
        val slotId = plannerRepository.insertSlot(
            PlanSlotEntity(date = date, name = "Dinner", time = LocalTime.of(19, 0))
        )

        val recipeId = database.recipeDao().insert(
            RecipeEntity(
                sourceType = "manual",
                sourceKey = "r-1",
                title = "Dal Makhani",
                servings = 4,
                status = RecipeEntity.STATUS_SAVED
            )
        )

        val entryId = plannerRepository.addCookEntry(slotId, recipeId, portions = 4, portionsEatenNow = 2)
        val entry = plannerRepository.getEntryById(entryId)
        assertNotNull(entry)
        assertEquals(4, entry!!.portions)
        assertEquals(2, entry.portionsEatenNow)

        // Mark as cooked
        plannerRepository.toggleEntryDone(entryId, note = "Delicious")
        val updated = plannerRepository.getEntryById(entryId)
        assertNotNull(updated!!.doneAt)

        // Check cook log
        val logs = database.cookLogDao().getByRecipeId(recipeId)
        assertEquals(1, logs.size)
        assertEquals("Delicious", logs.first().note)
    }

    @Test
    fun `copyPreviousWeekLayout copies slot structure to new week`() = runBlocking {
        val prevWeekMonday = LocalDate.of(2026, 10, 5)
        plannerRepository.insertSlot(PlanSlotEntity(date = prevWeekMonday, name = "Brunch", time = LocalTime.of(11, 0)))
        plannerRepository.insertSlot(PlanSlotEntity(date = prevWeekMonday, name = "Supper", time = LocalTime.of(20, 0)))

        val targetWeekMonday = LocalDate.of(2026, 10, 12)
        val copied = plannerRepository.copyPreviousWeekLayout(targetWeekMonday)
        assertTrue(copied)

        val newSlots = plannerRepository.getSlotsForDate(targetWeekMonday)
        assertEquals(2, newSlots.size)
        assertEquals(listOf("Brunch", "Supper"), newSlots.map { it.name })
    }

    @Test
    fun `buildFromPlan generates categorized grocery items and skips in-stock items`() = runBlocking {
        val weekStart = LocalDate.of(2026, 10, 12)
        val slotId = plannerRepository.insertSlot(
            PlanSlotEntity(date = weekStart, name = "Dinner")
        )

        val recipeId = database.recipeDao().insert(
            RecipeEntity(
                sourceType = "manual",
                sourceKey = "r-palak",
                title = "Palak Paneer",
                servings = 2,
                status = RecipeEntity.STATUS_SAVED
            )
        )

        database.ingredientDao().insertAll(
            listOf(
                IngredientEntity(
                    recipeId = recipeId,
                    position = 0,
                    name = "Fresh Spinach",
                    baseName = "spinach",
                    category = "vegetables",
                    quantity = 250.0,
                    unit = "g",
                    rawText = "250g spinach"
                ),
                IngredientEntity(
                    recipeId = recipeId,
                    position = 1,
                    name = "Paneer cubes",
                    baseName = "paneer",
                    category = "dairy",
                    quantity = 200.0,
                    unit = "g",
                    rawText = "200g paneer"
                )
            )
        )

        // Add 4 portions to plan (2x scale factor)
        plannerRepository.addCookEntry(slotId, recipeId, portions = 4)

        // In inventory: spinach is in_stock, paneer is low, eggs are out
        listsRepository.addInventoryItem(name = "Spinach", baseName = "spinach", category = "vegetables", status = InventoryItemEntity.STATUS_IN_STOCK)
        listsRepository.addInventoryItem(name = "Paneer", baseName = "paneer", category = "dairy", status = InventoryItemEntity.STATUS_LOW)
        listsRepository.addInventoryItem(name = "Eggs", baseName = "eggs", category = "dairy", status = InventoryItemEntity.STATUS_OUT)

        listsRepository.buildFromPlan(weekStart)

        val groceryItems = listsRepository.getGroceryItems(weekStart)

        // Spinach is in-stock -> excluded
        assertFalse(groceryItems.any { it.baseName == "spinach" })

        // Paneer is low -> included (200g * 2 = 400g)
        val paneer = groceryItems.firstOrNull { it.baseName == "paneer" }
        assertNotNull(paneer)
        assertTrue(paneer!!.quantityText?.contains("400 g") == true)
        assertTrue(paneer.quantityText?.contains("running low") == true)

        // Eggs are marked out in inventory -> added to grocery
        val eggs = groceryItems.firstOrNull { it.baseName == "eggs" }
        assertNotNull(eggs)
        assertEquals(GroceryItemEntity.SOURCE_INVENTORY, eggs!!.source)
    }

    @Test
    fun `checking grocery item updates inventory to in-stock`() = runBlocking {
        val id = listsRepository.addManualGroceryItem("Milk", "dairy", "1 L")
        val item = listsRepository.getGroceryItems().first { it.id == id }
        assertFalse(item.checked)

        listsRepository.toggleGroceryItemChecked(id, true)

        val updatedItem = listsRepository.getGroceryItems().first { it.id == id }
        assertTrue(updatedItem.checked)

        val inv = database.listsDao().getInventoryItemByBaseName("milk")
        assertNotNull(inv)
        assertEquals(InventoryItemEntity.STATUS_IN_STOCK, inv!!.status)
    }

    @Test
    fun `marking inventory item as out adds it to grocery list`() = runBlocking {
        val invId = listsRepository.addInventoryItem("Butter", "butter", "dairy", InventoryItemEntity.STATUS_IN_STOCK)
        listsRepository.setInventoryStatus(invId, InventoryItemEntity.STATUS_OUT)

        val grocery = listsRepository.getGroceryItems()
        val butterItem = grocery.firstOrNull { it.baseName == "butter" }
        assertNotNull(butterItem)
        assertEquals(GroceryItemEntity.SOURCE_INVENTORY, butterItem!!.source)
    }
}
