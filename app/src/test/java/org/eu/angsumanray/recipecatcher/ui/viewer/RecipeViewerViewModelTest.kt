package org.eu.angsumanray.recipecatcher.ui.viewer

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
import org.eu.angsumanray.recipecatcher.capture.CommentFetcher
import org.eu.angsumanray.recipecatcher.capture.CoverImageFetcher
import org.eu.angsumanray.recipecatcher.core.insta.InstagramPostFetcher
import org.eu.angsumanray.recipecatcher.core.llm.ExtractionInput
import org.eu.angsumanray.recipecatcher.core.llm.ExtractedRecipe
import org.eu.angsumanray.recipecatcher.core.llm.RecipeExtractor
import org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher
import org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData
import org.eu.angsumanray.recipecatcher.core.web.WebRecipeFetcher
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import org.eu.angsumanray.recipecatcher.data.repo.CookLogRepository
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import org.eu.angsumanray.recipecatcher.data.repo.ListsRepository
import org.eu.angsumanray.recipecatcher.data.repo.TagRepository
import org.eu.angsumanray.recipecatcher.data.settings.SecretStore
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeViewerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: RecipeDatabase
    private lateinit var repository: RecipeRepository
    private lateinit var cookLogRepository: CookLogRepository
    private lateinit var listsRepository: ListsRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var imageStorageManager: ImageStorageManager
    private lateinit var settingsStore: SettingsStore
    private lateinit var coverImageFetcher: CoverImageFetcher
    private var recipeId: Long = 0L

    class FakeRecipeExtractor : RecipeExtractor {
        override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> {
            return Result.failure(Exception("Not implemented"))
        }
    }

    @Before
    fun setup() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.starterSeedCallback)
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
        imageStorageManager = ImageStorageManager(context, okhttp3.OkHttpClient())
        repository = RecipeRepository(database, imageStorageManager)
        cookLogRepository = CookLogRepository(database, imageStorageManager)
        listsRepository = ListsRepository(
            database = database,
            listsDao = database.listsDao(),
            plannerDao = database.plannerDao(),
            recipeDao = database.recipeDao(),
            ingredientDao = database.ingredientDao()
        )
        tagRepository = TagRepository(database)
        settingsStore = SettingsStore(context, SecretStore(context))
        val offlineFetcher = object : HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>) = HttpResponseData(503, "")
        }
        coverImageFetcher = CoverImageFetcher(
            recipeRepository = repository,
            imageStorageManager = imageStorageManager,
            instagramPostFetcher = InstagramPostFetcher(emptyList()),
            webRecipeFetcher = WebRecipeFetcher(offlineFetcher),
            commentFetcher = CommentFetcher(context, settingsStore)
        )

        val recipe = RecipeEntity(
            sourceType = "manual",
            sourceKey = "VIEW_TEST",
            title = "Test Curry",
            servings = 4,
            status = RecipeEntity.STATUS_SAVED
        )
        val ingredients = listOf(
            IngredientEntity(
                recipeId = 0,
                position = 0,
                name = "Oil",
                baseName = "oil",
                category = "pantry",
                quantity = 2.0,
                quantityMax = 3.0,
                unit = "tbsp",
                rawText = "2-3 tbsp oil"
            ),
            IngredientEntity(
                recipeId = 0,
                position = 1,
                name = "Onion",
                baseName = "onion",
                category = "vegetables",
                quantity = 200.0,
                unit = "g",
                rawText = "200g onion"
            )
        )
        val steps = listOf(
            StepEntity(recipeId = 0, position = 0, text = "Heat oil in pan", timerSeconds = 120)
        )
        recipeId = repository.saveFullRecipe(recipe, ingredients, steps, emptyList())
    }

    private fun createViewModel(): RecipeViewerViewModel {
        val savedStateHandle = SavedStateHandle(mapOf("recipeId" to recipeId))
        return RecipeViewerViewModel(
            recipeRepository = repository,
            cookLogRepository = cookLogRepository,
            listsRepository = listsRepository,
            tagRepository = tagRepository,
            recipeExtractor = FakeRecipeExtractor(),
            imageStorageManager = imageStorageManager,
            settingsStore = settingsStore,
            coverImageFetcher = coverImageFetcher,
            savedStateHandle = savedStateHandle
        )
    }

    @After
    fun teardown() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `viewModel scales ingredients correctly when servings stepper changes`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(4, state.baseServings)
        assertEquals(4, state.currentServings)
        assertEquals(2, state.scaledIngredients.size)
        assertEquals(2.0, state.scaledIngredients[0].quantity!!, 0.001)
        assertEquals(3.0, state.scaledIngredients[0].quantityMax!!, 0.001)

        // Scale up to 8 servings (2x)
        viewModel.setServings(8)
        advanceUntilIdle()

        val state8 = viewModel.uiState.value
        assertEquals(8, state8.currentServings)
        assertEquals(4.0, state8.scaledIngredients[0].quantity!!, 0.001)
        assertEquals(6.0, state8.scaledIngredients[0].quantityMax!!, 0.001)
        assertEquals(400.0, state8.scaledIngredients[1].quantity!!, 0.001)

        // Scale down to 2 servings (0.5x)
        viewModel.setServings(2)
        advanceUntilIdle()

        val state2 = viewModel.uiState.value
        assertEquals(2, state2.currentServings)
        assertEquals(1.0, state2.scaledIngredients[0].quantity!!, 0.001)
        assertEquals(1.5, state2.scaledIngredients[0].quantityMax!!, 0.001)
        assertEquals(100.0, state2.scaledIngredients[1].quantity!!, 0.001)
    }

    @Test
    fun `ingredient session checklist toggle and clear`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val ingId = viewModel.uiState.value.rawIngredients.first().id
        assertFalse(viewModel.uiState.value.checkedIngredientIds.contains(ingId))

        viewModel.toggleIngredientCheck(ingId)
        assertTrue(viewModel.uiState.value.checkedIngredientIds.contains(ingId))

        viewModel.clearIngredientChecks()
        assertFalse(viewModel.uiState.value.checkedIngredientIds.contains(ingId))
    }

    @Test
    fun `favourite and personal note toggle in viewModel`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.recipe!!.isFavourite)
        viewModel.toggleFavourite()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.recipe!!.isFavourite)

        viewModel.updatePersonalNote("Add extra salt")
        advanceUntilIdle()

        assertEquals("Add extra salt", repository.getRecipe(recipeId)!!.personalNote)
    }

    @Test
    fun `mark as cooked adds log to cookLogRepository`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.timesCooked)

        val date = LocalDate.of(2026, 10, 15)
        viewModel.markAsCooked(date, "Great result", null)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.timesCooked)
        assertEquals("2026-10-15", viewModel.uiState.value.lastCookedDate)
    }

    @Test
    fun `getSharePlainText formats recipe into clean plain text without markdown`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val plainText = viewModel.getSharePlainText()
        assertNotNull(plainText)
        assertTrue(plainText!!.contains("Test Curry"))
        assertTrue(plainText.contains("Ingredients:"))
        assertTrue(plainText.contains("Oil"))
        assertTrue(plainText.contains("Onion"))
        assertTrue(plainText.contains("Instructions:"))
        assertTrue(plainText.contains("1. Heat oil in pan"))
        assertFalse(plainText.contains("**"))
        assertFalse(plainText.contains("##"))
    }

    @Test
    fun `mark as cooked with pantry reconciliation updates inventory status`() = runTest(testDispatcher) {
        // Pre-populate inventory with onion as IN_STOCK
        listsRepository.addInventoryItem(name = "Onion", baseName = "onion", category = "vegetables", status = "in_stock")
        val viewModel = createViewModel()
        advanceUntilIdle()

        val matchedInventory = listsRepository.getAllInventoryItems().first { it.baseName == "onion" }
        assertEquals("in_stock", matchedInventory.status)

        // Mark as cooked and reconcile onion to OUT
        val reconciliationItems = listOf(
            org.eu.angsumanray.recipecatcher.core.planner.PantryReconciliationItem(
                ingredientName = "Onion",
                baseName = "onion",
                category = "vegetables",
                action = org.eu.angsumanray.recipecatcher.core.planner.PantryAction.OUT
            )
        )

        viewModel.markAsCooked(
            date = LocalDate.now(),
            note = "Cooked curry",
            photoUri = null,
            reconciliationItems = reconciliationItems
        )
        advanceUntilIdle()

        val updatedInventory = listsRepository.getAllInventoryItems().first { it.baseName == "onion" }
        assertEquals("out", updatedInventory.status)
        assertEquals(1, viewModel.uiState.value.timesCooked)
    }

    @Test
    fun `fetch image is offered only for linked recipes without an image`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.canFetchImage)

        val instagram = RecipeEntity(sourceType = "instagram", sourceKey = "ABC123", sourceUrl = "https://www.instagram.com/p/ABC123/")
        assertTrue(coverImageFetcher.canFetch(instagram))
        assertTrue(coverImageFetcher.canFetch(instagram.copy(imagePath = "images/missing.jpg")))

        val saved = imageStorageManager.saveImageBytes(42L, byteArrayOf(1, 2, 3))
        assertFalse(coverImageFetcher.canFetch(instagram.copy(imagePath = saved)))
    }
}
