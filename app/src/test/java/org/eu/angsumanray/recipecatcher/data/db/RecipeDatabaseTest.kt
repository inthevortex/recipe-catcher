package org.eu.angsumanray.recipecatcher.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.data.db.entity.CookLogEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanEntryEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanSlotEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.repo.CaptureStart
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import java.time.LocalDate
import java.time.LocalTime
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
class RecipeDatabaseTest {

    private lateinit var database: RecipeDatabase
    private lateinit var repository: RecipeRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.starterSeedCallback)
            .allowMainThreadQueries()
            .build()
        repository = RecipeRepository(database)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `starter lists are seeded when the database is created`() = runBlocking {
        val tags = database.tagDao().getByKind(TagEntity.KIND_TAG)
        val diet = database.tagDao().getByKind(TagEntity.KIND_DIET)
        val equipment = database.tagDao().getByKind(TagEntity.KIND_EQUIPMENT)
        val consumables = database.tagDao().getByKind(TagEntity.KIND_CONSUMABLE)

        assertEquals(StarterLists.TAGS.size, tags.size)
        assertEquals(StarterLists.DIET.size, diet.size)
        assertEquals(StarterLists.EQUIPMENT.size, equipment.size)
        assertEquals(StarterLists.CONSUMABLES.size, consumables.size)
        assertEquals("breakfast", tags.first().name)
    }

    @Test
    fun `tag names are unique per kind ignoring case`() = runBlocking {
        assertEquals(-1L, database.tagDao().insert(TagEntity(kind = TagEntity.KIND_TAG, name = "BREAKFAST")))
        assertTrue(database.tagDao().insert(TagEntity(kind = TagEntity.KIND_EQUIPMENT, name = "breakfast")) > 0)
    }

    @Test
    fun `saveFullRecipe writes recipe and updates FTS search`() = runBlocking {
        val tagId = database.tagDao().getByKind(TagEntity.KIND_TAG).first { it.name == "paneer" }.id

        val recipe = RecipeEntity(
            sourceType = "instagram",
            sourceKey = "ABC1234",
            title = "Paneer Butter Masala",
            captionRaw = "Authentic paneer recipe with rich gravy.",
            authorHandle = "chef_john"
        )
        val ingredients = listOf(
            IngredientEntity(
                recipeId = 0,
                position = 0,
                name = "paneer",
                baseName = "paneer",
                category = "dairy",
                quantity = 250.0,
                unit = "g",
                rawText = "250g paneer cubes"
            )
        )
        val steps = listOf(
            StepEntity(recipeId = 0, position = 0, text = "Cook paneer in butter.")
        )

        val recipeId = repository.saveFullRecipe(
            recipe = recipe,
            ingredients = ingredients,
            steps = steps,
            tagIds = listOf(tagId)
        )

        assertTrue(recipeId > 0)
        val saved = repository.getRecipe(recipeId)
        assertNotNull(saved)
        assertEquals(RecipeEntity.STATUS_SAVED, saved?.status)
        assertEquals("Paneer Butter Masala", saved?.title)

        // FTS prefix search for "pan*"
        val searchResults = repository.search("pan")
        assertEquals(1, searchResults.size)
        assertEquals("Paneer Butter Masala", searchResults[0].title)

        // Delete recipe removes from FTS
        repository.deleteRecipe(recipeId)
        val afterDeleteSearch = repository.search("pan")
        assertTrue(afterDeleteSearch.isEmpty())
    }

    @Test
    fun `startCapture dedupes on source key and never replaces the existing row`() = runBlocking {
        val first = repository.startCapture("Look https://www.instagram.com/p/Dup_1/?igsh=abc")
        assertTrue(first is CaptureStart.Created)
        val id = (first as CaptureStart.Created).recipeId
        assertEquals(SourceType.INSTAGRAM, first.sourceType)
        database.cookLogDao().insert(CookLogEntity(recipeId = id, cookedOn = LocalDate.of(2026, 10, 1)))

        val again = repository.startCapture("https://instagram.com/reel/Dup_1")
        assertEquals(CaptureStart.Existing(id), again)
        assertEquals(id, repository.insertPendingRecipe("instagram", "Dup_1"))
        assertEquals(1, database.cookLogDao().getByRecipeId(id).size)

        val row = repository.getRecipe(id)!!
        assertEquals("https://www.instagram.com/p/Dup_1/", row.sourceUrl)
        assertEquals(RecipeEntity.STATUS_PENDING_FETCH, row.status)
        assertEquals(CaptureStart.NoLink, repository.startCapture("no link here"))
    }

    @Test
    fun `saving an existing recipe keeps its cook log and plan entries`() = runBlocking {
        val id = repository.insertPendingRecipe("instagram", "KEEP1")
        database.cookLogDao().insert(CookLogEntity(recipeId = id, cookedOn = LocalDate.of(2026, 10, 1)))
        val slotId = database.plannerDao().insertSlot(PlanSlotEntity(date = LocalDate.of(2026, 10, 20), name = "Dinner", time = LocalTime.of(20, 0)))
        database.plannerDao().insertEntry(PlanEntryEntity(slotId = slotId, kind = "cook", recipeId = id, portions = 4))

        val existing = repository.getRecipe(id)!!.copy(title = "Saved title")
        val savedId = repository.saveFullRecipe(existing, emptyList(), emptyList(), emptyList())

        assertEquals(id, savedId)
        assertEquals(RecipeEntity.STATUS_SAVED, repository.getRecipe(id)?.status)
        assertEquals(1, database.cookLogDao().getByRecipeId(id).size)
        assertEquals(id, database.plannerDao().getEntriesForSlot(slotId).single().recipeId)
    }

    @Test
    fun `dates and times round trip as ISO text`() {
        val converters = Converters()
        assertEquals("2026-03-09", converters.fromLocalDate(LocalDate.of(2026, 3, 9)))
        assertEquals(LocalDate.of(2026, 3, 9), converters.toLocalDate("2026-03-09"))
        assertEquals("07:05", converters.fromLocalTime(LocalTime.of(7, 5)))
        assertEquals(LocalTime.of(7, 5), converters.toLocalTime("07:05"))
    }

    @Test
    fun `fts query sanitizer keeps non latin words and strips fts syntax`() {
        assertEquals("pan* tikka*", RecipeRepository.sanitizeFtsQuery("pan \"tikka\"*"))
        assertEquals("crème* brûlée*", RecipeRepository.sanitizeFtsQuery("crème brûlée"))
        assertEquals("চিকেন* বিরিয়ানি*", RecipeRepository.sanitizeFtsQuery("চিকেন বিরিয়ানি"))
        assertEquals("", RecipeRepository.sanitizeFtsQuery("*:-"))
    }
}
