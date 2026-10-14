package org.eu.angsumanray.recipecatcher.ui.cook

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import org.eu.angsumanray.recipecatcher.data.repo.CookLogRepository
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CookingModeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: RecipeDatabase
    private lateinit var repository: RecipeRepository
    private lateinit var cookLogRepository: CookLogRepository
    private lateinit var imageStorageManager: ImageStorageManager
    private var recipeId: Long = 0L

    @Before
    fun setup() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
        imageStorageManager = ImageStorageManager(context, okhttp3.OkHttpClient())
        repository = RecipeRepository(database, imageStorageManager)
        cookLogRepository = CookLogRepository(database, imageStorageManager)

        val recipe = RecipeEntity(
            sourceType = "manual",
            sourceKey = "COOK_TEST",
            title = "Cooking Test Recipe",
            servings = 2,
            status = RecipeEntity.STATUS_SAVED
        )
        val ingredients = listOf(
            IngredientEntity(
                recipeId = 0,
                position = 0,
                name = "Pasta",
                baseName = "pasta",
                category = "grains & flours",
                quantity = 200.0,
                unit = "g",
                rawText = "200g pasta"
            )
        )
        val steps = listOf(
            StepEntity(recipeId = 0, position = 0, text = "Boil water with salt", timerSeconds = 60),
            StepEntity(recipeId = 0, position = 1, text = "Cook pasta for 10 minutes", timerSeconds = 600),
            StepEntity(recipeId = 0, position = 2, text = "Drain and serve with sauce", timerSeconds = null)
        )
        recipeId = repository.saveFullRecipe(recipe, ingredients, steps, emptyList())
    }

    @After
    fun teardown() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `step navigation boundaries and current step index`() = runTest(testDispatcher) {
        val savedStateHandle = SavedStateHandle(mapOf("recipeId" to recipeId))
        val viewModel = CookingModeViewModel(
            recipeRepository = repository,
            cookLogRepository = cookLogRepository,
            imageStorageManager = imageStorageManager,
            savedStateHandle = savedStateHandle
        )
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.currentStepIndex)
        assertEquals(3, viewModel.uiState.value.steps.size)

        // Previous step at 0 stays at 0
        viewModel.previousStep()
        assertEquals(0, viewModel.uiState.value.currentStepIndex)

        // Advance to step 1
        viewModel.nextStep()
        assertEquals(1, viewModel.uiState.value.currentStepIndex)

        // Advance to step 2 (last step)
        viewModel.nextStep()
        assertEquals(2, viewModel.uiState.value.currentStepIndex)

        // Next step at end stays at 2
        viewModel.nextStep()
        assertEquals(2, viewModel.uiState.value.currentStepIndex)

        // Back to 1
        viewModel.previousStep()
        assertEquals(1, viewModel.uiState.value.currentStepIndex)
    }

    @Test
    fun `timers are initialized and can be started, paused, and reset`() = runTest(testDispatcher) {
        val savedStateHandle = SavedStateHandle(mapOf("recipeId" to recipeId))
        val viewModel = CookingModeViewModel(
            recipeRepository = repository,
            cookLogRepository = cookLogRepository,
            imageStorageManager = imageStorageManager,
            savedStateHandle = savedStateHandle
        )
        advanceUntilIdle()

        val timers = viewModel.uiState.value.timers
        // Steps 0 (60s) and 1 (600s) have timers, step 2 does not
        assertEquals(2, timers.size)
        assertEquals(60, timers[0]!!.remainingSeconds)
        assertEquals(600, timers[1]!!.remainingSeconds)
        assertFalse(timers[0]!!.isRunning)

        // Start step 0 timer
        viewModel.startTimer(0)
        assertTrue(viewModel.uiState.value.timers[0]!!.isRunning)

        // Advance 3 seconds
        advanceTimeBy(3500)
        assertEquals(57, viewModel.uiState.value.timers[0]!!.remainingSeconds)

        // Pause timer
        viewModel.pauseTimer(0)
        assertFalse(viewModel.uiState.value.timers[0]!!.isRunning)
        assertEquals(57, viewModel.uiState.value.timers[0]!!.remainingSeconds)

        // Reset timer
        viewModel.resetTimer(0)
        assertEquals(60, viewModel.uiState.value.timers[0]!!.remainingSeconds)
        assertFalse(viewModel.uiState.value.timers[0]!!.isRunning)
    }

    @Test
    fun `cooking mode ingredients checklist and mark as cooked completion`() = runTest(testDispatcher) {
        val savedStateHandle = SavedStateHandle(mapOf("recipeId" to recipeId))
        val viewModel = CookingModeViewModel(
            recipeRepository = repository,
            cookLogRepository = cookLogRepository,
            imageStorageManager = imageStorageManager,
            savedStateHandle = savedStateHandle
        )
        advanceUntilIdle()

        val ingId = viewModel.uiState.value.ingredients.first().id
        viewModel.toggleIngredientCheck(ingId)
        assertTrue(viewModel.uiState.value.checkedIngredientIds.contains(ingId))

        var callbackCalled = false
        viewModel.markAsCooked(
            date = LocalDate.of(2026, 10, 15),
            note = "Al dente",
            photoUri = null
        ) {
            callbackCalled = true
        }
        advanceUntilIdle()

        assertTrue(callbackCalled)
        assertTrue(viewModel.uiState.value.isMarkedCooked)
        assertEquals(1, cookLogRepository.getTimesCooked(recipeId))
    }
}
