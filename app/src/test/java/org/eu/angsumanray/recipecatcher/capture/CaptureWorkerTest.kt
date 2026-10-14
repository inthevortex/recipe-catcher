package org.eu.angsumanray.recipecatcher.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.eu.angsumanray.recipecatcher.core.insta.InstagramPostFetcher
import org.eu.angsumanray.recipecatcher.core.insta.PostData
import org.eu.angsumanray.recipecatcher.core.insta.PostFetchStrategy
import org.eu.angsumanray.recipecatcher.core.llm.ExtractedIngredient
import org.eu.angsumanray.recipecatcher.core.llm.ExtractedIngredientGroup
import org.eu.angsumanray.recipecatcher.core.llm.ExtractedRecipe
import org.eu.angsumanray.recipecatcher.core.llm.ExtractedStep
import org.eu.angsumanray.recipecatcher.core.llm.ExtractionInput
import org.eu.angsumanray.recipecatcher.core.llm.LlmUsage
import org.eu.angsumanray.recipecatcher.core.llm.NoRecipeFoundException
import org.eu.angsumanray.recipecatcher.core.llm.RecipeExtractor
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.StarterLists
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import org.eu.angsumanray.recipecatcher.data.settings.SecretStore
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
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
class CaptureWorkerTest {

    private lateinit var context: Context
    private lateinit var database: RecipeDatabase
    private lateinit var recipeRepository: RecipeRepository
    private lateinit var secretStore: SecretStore
    private lateinit var settingsStore: SettingsStore
    private lateinit var imageStorageManager: ImageStorageManager

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.tagDao().insertAll(StarterLists.createInitialEntities())
        recipeRepository = RecipeRepository(database)
        secretStore = SecretStore(context)
        settingsStore = SettingsStore(context, secretStore)
        settingsStore.saveApiKey("sk-ant-test-key")
        imageStorageManager = ImageStorageManager(context, OkHttpClient())
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun createWorker(
        recipeId: Long,
        postFetcher: InstagramPostFetcher,
        extractor: RecipeExtractor
    ): CaptureWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker {
                return CaptureWorker(
                    appContext,
                    workerParameters,
                    database,
                    recipeRepository,
                    postFetcher,
                    imageStorageManager,
                    extractor,
                    settingsStore
                )
            }
        }

        return TestListenableWorkerBuilder<CaptureWorker>(context)
            .setInputData(workDataOf(CaptureWorker.KEY_RECIPE_ID to recipeId))
            .setWorkerFactory(factory)
            .build()
    }

    @Test
    fun `CaptureWorker transitions from pending_fetch to needs_review on success`() = runBlocking {
        val recipeId = recipeRepository.insertPendingRecipe(
            sourceType = "instagram",
            sourceKey = "ABC_123",
            sourceUrl = "https://www.instagram.com/p/ABC_123/"
        )

        val mockStrategy = object : PostFetchStrategy {
            override val name: String = "Mock"
            override suspend fun fetch(shortcode: String): PostData {
                return PostData(
                    caption = "Delicious Paneer Tikka recipe!\nIngredients: 250g paneer, curd, spices.\n\n#food",
                    author = "chef_paneer",
                    imageUrl = null
                )
            }
        }
        val postFetcher = InstagramPostFetcher(listOf(mockStrategy))

        val mockExtractor = object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> {
                return Result.success(
                    ExtractedRecipe(
                        isRecipe = true,
                        title = "Paneer Tikka",
                        description = "Grilled spiced paneer cubes",
                        servings = 4,
                        prepMinutes = 15,
                        cookMinutes = 15,
                        totalMinutes = 30,
                        cuisine = "Indian",
                        complexity = "easy",
                        complexityReason = "Quick marination and grill",
                        spiceLevel = "medium",
                        dietType = "vegetarian",
                        contains = listOf("dairy"),
                        kcalPerServing = 320,
                        makeAhead = true,
                        freezerFriendly = false,
                        ingredientGroups = listOf(
                            ExtractedIngredientGroup(
                                name = "Main",
                                items = listOf(
                                    ExtractedIngredient(
                                        quantity = 250.0,
                                        quantityMax = null,
                                        unit = "g",
                                        name = "paneer",
                                        baseName = "paneer",
                                        category = "dairy",
                                        note = null,
                                        rawText = "250g paneer"
                                    )
                                )
                            )
                        ),
                        steps = listOf(
                            ExtractedStep("Marinate paneer.", null),
                            ExtractedStep("Grill for 15 mins.", 900)
                        ),
                        equipment = listOf("kadai"),
                        consumables = listOf("skewers"),
                        tags = listOf("paneer", "indian", "dinner"),
                        newListSuggestions = emptyList(),
                        confidence = 0.95,
                        usage = LlmUsage(inputTokens = 1500, outputTokens = 600, cacheReadTokens = 300)
                    )
                )
            }
        }

        val worker = createWorker(recipeId, postFetcher, mockExtractor)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)

        val updated = recipeRepository.getRecipe(recipeId)
        assertNotNull(updated)
        assertEquals(RecipeEntity.STATUS_NEEDS_REVIEW, updated?.status)
        assertEquals("Paneer Tikka", updated?.title)
        assertEquals("chef_paneer", updated?.authorHandle)
        assertEquals(4, updated?.servings)
        assertEquals(30, updated?.totalMin)

        // Verify ingredients and steps saved
        val ingredients = database.ingredientDao().getByRecipeId(recipeId)
        assertEquals(1, ingredients.size)
        assertEquals("paneer", ingredients[0].name)

        val steps = database.stepDao().getByRecipeId(recipeId)
        assertEquals(2, steps.size)

        // Verify LLM usage logged
        val usages = database.llmUsageDao().getAll()
        assertEquals(1, usages.size)
        assertEquals(1500, usages[0].inputTokens)
        assertEquals(600, usages[0].outputTokens)
    }

    @Test
    fun `CaptureWorker sets no_recipe_found when model returns NoRecipeFoundException`() = runBlocking {
        val recipeId = recipeRepository.insertPendingRecipe(
            sourceType = "instagram",
            sourceKey = "XYZ_456"
        )

        val mockStrategy = object : PostFetchStrategy {
            override val name: String = "Mock"
            override suspend fun fetch(shortcode: String): PostData {
                return PostData(caption = "Check link in bio!", author = "influencer", imageUrl = null)
            }
        }
        val postFetcher = InstagramPostFetcher(listOf(mockStrategy))

        val mockExtractor = object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> {
                return Result.failure(NoRecipeFoundException("No recipe found"))
            }
        }

        val worker = createWorker(recipeId, postFetcher, mockExtractor)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)

        val updated = recipeRepository.getRecipe(recipeId)
        assertEquals(RecipeEntity.STATUS_NO_RECIPE_FOUND, updated?.status)
        assertEquals("No recipe in caption", updated?.errorMessage)
    }
}
