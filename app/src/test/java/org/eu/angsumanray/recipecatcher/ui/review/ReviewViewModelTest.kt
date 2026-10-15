package org.eu.angsumanray.recipecatcher.ui.review

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.dao.TagDao
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: RecipeDatabase
    private lateinit var repository: RecipeRepository
    private lateinit var tagDao: TagDao
    private lateinit var context: Context

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.starterSeedCallback)
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
        repository = RecipeRepository(database)
        tagDao = database.tagDao()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `initial pending_fetch status shows isExtracting true with Instagram fetch message`() = runTest(testDispatcher) {
        val recipeId = repository.insertPendingRecipe(
            sourceType = "instagram",
            sourceKey = "test_insta_1",
            sourceUrl = "https://instagram.com/p/123"
        )
        advanceUntilIdle()

        val viewModel = ReviewViewModel(
            recipeRepository = repository,
            tagDao = tagDao,
            savedStateHandle = SavedStateHandle(mapOf("recipeId" to recipeId)),
            context = context
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.isExtracting)
        assertFalse(state.isFailed)
        assertEquals("Fetching recipe details from Instagram...", state.extractionStage)
    }

    @Test
    fun `status transition to pending_extract updates extractionStage to analyzing`() = runTest(testDispatcher) {
        val recipeId = repository.insertPendingRecipe(
            sourceType = "instagram",
            sourceKey = "test_insta_2",
            sourceUrl = "https://instagram.com/p/456"
        )
        advanceUntilIdle()

        val viewModel = ReviewViewModel(
            recipeRepository = repository,
            tagDao = tagDao,
            savedStateHandle = SavedStateHandle(mapOf("recipeId" to recipeId)),
            context = context
        )
        advanceUntilIdle()

        repository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_PENDING_EXTRACT)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isExtracting)
        assertEquals("Claude is analyzing ingredients and instructions...", state.extractionStage)
    }

    @Test
    fun `status transition to needs_review populates form and clears isExtracting`() = runTest(testDispatcher) {
        val recipeId = repository.insertPendingRecipe(
            sourceType = "instagram",
            sourceKey = "test_insta_3",
            sourceUrl = "https://instagram.com/p/789"
        )
        advanceUntilIdle()

        val viewModel = ReviewViewModel(
            recipeRepository = repository,
            tagDao = tagDao,
            savedStateHandle = SavedStateHandle(mapOf("recipeId" to recipeId)),
            context = context
        )
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isExtracting)

        // Simulate extraction completion
        val existing = repository.getRecipe(recipeId)!!
        val updated = existing.copy(
            title = "Extracted Pasta",
            description = "Delicious creamy pasta",
            servings = 2,
            prepMin = 10,
            cookMin = 15,
            status = RecipeEntity.STATUS_NEEDS_REVIEW
        )
        repository.updateRecipe(updated)
        database.ingredientDao().insertAll(
            listOf(
                IngredientEntity(
                    recipeId = recipeId,
                    name = "Pasta",
                    baseName = "pasta",
                    category = "grains & flours",
                    rawText = "200g pasta",
                    quantity = 200.0,
                    unit = "g",
                    position = 0
                )
            )
        )
        database.stepDao().insertAll(
            listOf(
                StepEntity(recipeId = recipeId, position = 0, text = "Boil water and cook pasta")
            )
        )
        repository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_NEEDS_REVIEW)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isExtracting)
        assertFalse(state.isFailed)
        assertEquals("Extracted Pasta", state.title)
        assertEquals("Delicious creamy pasta", state.description)
        assertEquals(2, state.servings)
        assertEquals(1, state.ingredients.size)
        assertEquals("Pasta", state.ingredients[0].name)
        assertEquals(1, state.steps.size)
        assertEquals("Boil water and cook pasta", state.steps[0].text)
    }

    @Test
    fun `status transition to failed_extract sets isFailed true and reports error message`() = runTest(testDispatcher) {
        val recipeId = repository.insertPendingRecipe(
            sourceType = "instagram",
            sourceKey = "test_insta_4",
            sourceUrl = "https://instagram.com/p/fail"
        )
        advanceUntilIdle()

        val viewModel = ReviewViewModel(
            recipeRepository = repository,
            tagDao = tagDao,
            savedStateHandle = SavedStateHandle(mapOf("recipeId" to recipeId)),
            context = context
        )
        advanceUntilIdle()

        repository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_FAILED_EXTRACT, "No recipe ingredients found in post")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isExtracting)
        assertTrue(state.isFailed)
        assertEquals("No recipe ingredients found in post", state.errorMessage)
    }

    @Test
    fun `dismissFailure clears failed state and enables form editing`() = runTest(testDispatcher) {
        val recipeId = repository.insertPendingRecipe(
            sourceType = "instagram",
            sourceKey = "test_insta_5",
            sourceUrl = "https://instagram.com/p/dismiss"
        )
        repository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_FAILED_EXTRACT, "Network timeout")
        advanceUntilIdle()

        val viewModel = ReviewViewModel(
            recipeRepository = repository,
            tagDao = tagDao,
            savedStateHandle = SavedStateHandle(mapOf("recipeId" to recipeId)),
            context = context
        )
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isFailed)

        viewModel.dismissFailure()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isFailed)
        assertFalse(state.isExtracting)
    }

    @Test
    fun `retryCapture resets failure and restarts extraction`() = runTest(testDispatcher) {
        val recipeId = repository.insertPendingRecipe(
            sourceType = "instagram",
            sourceKey = "test_insta_6",
            sourceUrl = "https://instagram.com/p/retry"
        )
        repository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_FAILED_EXTRACT, "Transient API error")
        advanceUntilIdle()

        val viewModel = ReviewViewModel(
            recipeRepository = repository,
            tagDao = tagDao,
            savedStateHandle = SavedStateHandle(mapOf("recipeId" to recipeId)),
            context = context
        )
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isFailed)

        viewModel.retryCapture()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isFailed)
        assertTrue(state.isExtracting)
    }

    @Test
    fun `saveRecipe saves full recipe with saved status`() = runTest(testDispatcher) {
        val recipeId = repository.insertPendingRecipe(
            sourceType = "instagram",
            sourceKey = "test_insta_7",
            sourceUrl = "https://instagram.com/p/save"
        )
        repository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_NEEDS_REVIEW)
        advanceUntilIdle()

        val viewModel = ReviewViewModel(
            recipeRepository = repository,
            tagDao = tagDao,
            savedStateHandle = SavedStateHandle(mapOf("recipeId" to recipeId)),
            context = context
        )
        advanceUntilIdle()

        viewModel.updateTitle("Hand-Edited Carbonara")
        viewModel.updateServings(3)

        var savedSuccess = false
        viewModel.saveRecipe { savedSuccess = true }
        advanceUntilIdle()

        assertTrue(savedSuccess)
        val saved = repository.getRecipe(recipeId)
        assertNotNull(saved)
        assertEquals("Hand-Edited Carbonara", saved?.title)
        assertEquals(3, saved?.servings)
        assertEquals(RecipeEntity.STATUS_SAVED, saved?.status)
    }
}
