package org.eu.angsumanray.recipecatcher.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
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
            .allowMainThreadQueries()
            .build()
        repository = RecipeRepository(database)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `starter lists seeder inserts initial tags`() = runBlocking {
        val initialEntities = StarterLists.createInitialEntities()
        database.tagDao().insertAll(initialEntities)

        val tags = database.tagDao().getByKind(TagEntity.KIND_TAG)
        val diet = database.tagDao().getByKind(TagEntity.KIND_DIET)
        val equipment = database.tagDao().getByKind(TagEntity.KIND_EQUIPMENT)
        val consumables = database.tagDao().getByKind(TagEntity.KIND_CONSUMABLE)

        assertEquals(StarterLists.TAGS.size, tags.size)
        assertEquals(StarterLists.DIET.size, diet.size)
        assertEquals(StarterLists.EQUIPMENT.size, equipment.size)
        assertEquals(StarterLists.CONSUMABLES.size, consumables.size)
    }

    @Test
    fun `saveFullRecipe writes recipe and updates FTS search`() = runBlocking {
        val tagId = database.tagDao().insert(TagEntity(kind = TagEntity.KIND_TAG, name = "paneer"))

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
}
