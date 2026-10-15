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
import kotlinx.coroutines.flow.first
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
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.StarterLists
import org.eu.angsumanray.recipecatcher.data.db.entity.ImportItemEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportWorkerTest {

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
        settingsStore.setImportPaused(false)
        settingsStore.setImportCooldownUntil(0L)
        imageStorageManager = ImageStorageManager(context, OkHttpClient())
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun buildWorker(
        postFetcher: InstagramPostFetcher = createMockFetcher(),
        extractor: RecipeExtractor = createMockExtractor()
    ): ImportWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker {
                return ImportWorker(
                    appContext = appContext,
                    workerParams = workerParameters,
                    database = database,
                    recipeRepository = recipeRepository,
                    instagramPostFetcher = postFetcher,
                    imageStorageManager = imageStorageManager,
                    recipeExtractor = extractor,
                    settingsStore = settingsStore
                )
            }
        }

        return TestListenableWorkerBuilder<ImportWorker>(context)
            .setWorkerFactory(factory)
            .setInputData(workDataOf(ImportWorker.KEY_INTERVAL_MILLIS to 0L))
            .build()
    }

    @Test
    fun doWork_alreadySavedRecipe_marksItemDone() = runBlocking {
        // Pre-insert recipe as saved
        val existingRecipe = RecipeEntity(
            sourceType = SourceType.INSTAGRAM.name.lowercase(),
            sourceKey = "ALREADY_SAVED",
            title = "Existing Pasta",
            status = RecipeEntity.STATUS_SAVED
        )
        val recipeId = database.recipeDao().insert(existingRecipe)

        // Insert queued import item
        val importItem = ImportItemEntity(
            url = "https://www.instagram.com/p/ALREADY_SAVED/",
            sourceKey = "ALREADY_SAVED",
            status = ImportItemEntity.STATUS_QUEUED
        )
        val itemId = database.importItemDao().insert(importItem)

        val worker = buildWorker()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val updatedItem = database.importItemDao().getBySourceKey("ALREADY_SAVED")
        assertNotNull(updatedItem)
        assertEquals(ImportItemEntity.STATUS_DONE, updatedItem?.status)
        assertEquals(recipeId, updatedItem?.recipeId)
    }

    @Test
    fun doWork_newPost_extractsAndSavesWithReviewedFalseAndCollectionTag() = runBlocking {
        val mockFetcher = createMockFetcher("Delicious Curry with rice", "chef_john")
        val mockExtractor = createMockExtractor(
            title = "Delicious Curry",
            tags = listOf("Dinner")
        )

        // Queue post with collection "Curry Night"
        val importItem = ImportItemEntity(
            url = "https://www.instagram.com/p/CURRY123/",
            sourceKey = "CURRY123",
            collection = "Curry Night",
            status = ImportItemEntity.STATUS_QUEUED
        )
        database.importItemDao().insert(importItem)
        settingsStore.setTagWithCollections(true)

        val worker = buildWorker(mockFetcher, mockExtractor)
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val updatedItem = database.importItemDao().getBySourceKey("CURRY123")
        assertEquals(ImportItemEntity.STATUS_DONE, updatedItem?.status)

        val recipe = database.recipeDao().getBySourceKey("CURRY123")
        assertNotNull(recipe)
        assertEquals(RecipeEntity.STATUS_SAVED, recipe?.status)
        assertEquals("Delicious Curry", recipe?.title)
        assertFalse("Bulk imported recipe must have reviewed = false", recipe!!.reviewed)

        // Collection tag should be attached
        val tags = database.recipeTagDao().getTagsForRecipe(recipe.id)
        assertTrue(tags.any { it.name.equals("Curry Night", ignoreCase = true) })
    }

    @Test
    fun doWork_noRecipeInPost_marksSkipped() = runBlocking {
        val mockFetcher = createMockFetcher("Just a photo of sunset", "user1")
        val failingExtractor = object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> {
                return Result.failure(NoRecipeFoundException("No recipe found", LlmUsage(10, 5, 0)))
            }
        }

        database.importItemDao().insert(
            ImportItemEntity(
                url = "https://www.instagram.com/p/SUNSET/",
                sourceKey = "SUNSET",
                status = ImportItemEntity.STATUS_QUEUED
            )
        )

        val worker = buildWorker(mockFetcher, failingExtractor)
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val item = database.importItemDao().getBySourceKey("SUNSET")
        assertEquals(ImportItemEntity.STATUS_SKIPPED, item?.status)

        val recipe = database.recipeDao().getBySourceKey("SUNSET")
        assertEquals(RecipeEntity.STATUS_NO_RECIPE_FOUND, recipe?.status)
    }

    @Test
    fun doWork_threeConsecutiveFailures_enforcesCooldown() = runBlocking {
        val failingFetcher = InstagramPostFetcher(emptyList())

        database.importItemDao().insertAll(
            listOf(
                ImportItemEntity(url = "https://instagram.com/p/F1/", sourceKey = "F1"),
                ImportItemEntity(url = "https://instagram.com/p/F2/", sourceKey = "F2"),
                ImportItemEntity(url = "https://instagram.com/p/F3/", sourceKey = "F3"),
                ImportItemEntity(url = "https://instagram.com/p/F4/", sourceKey = "F4")
            )
        )

        val worker = buildWorker(postFetcher = failingFetcher)
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        // 3 consecutive failures should set cooldown
        val cooldownUntil = settingsStore.importCooldownUntilFlow.first()
        assertTrue(cooldownUntil > System.currentTimeMillis())

        // 4th item should still be queued because worker halted after 3 failures
        val f4 = database.importItemDao().getBySourceKey("F4")
        assertEquals(ImportItemEntity.STATUS_QUEUED, f4?.status)
    }

    @Test
    fun doWork_paused_doesNotProcessItems() = runBlocking {
        settingsStore.setImportPaused(true)

        database.importItemDao().insert(
            ImportItemEntity(url = "https://instagram.com/p/PAUSED/", sourceKey = "PAUSED")
        )

        val worker = buildWorker()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val item = database.importItemDao().getBySourceKey("PAUSED")
        assertEquals(ImportItemEntity.STATUS_QUEUED, item?.status)
    }

    private fun createMockFetcher(caption: String = "Recipe caption", author: String = "chef"): InstagramPostFetcher {
        val strategy = object : PostFetchStrategy {
            override val name: String = "mock"
            override suspend fun fetch(shortcode: String): PostData? {
                return PostData(
                    caption = caption,
                    imageUrl = null,
                    author = author
                )
            }
        }
        return InstagramPostFetcher(listOf(strategy))
    }

    private fun createMockExtractor(
        title: String = "Test Recipe",
        tags: List<String> = emptyList()
    ): RecipeExtractor {
        return object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> {
                return Result.success(
                    ExtractedRecipe(
                        isRecipe = true,
                        title = title,
                        description = "Test description",
                        servings = 4,
                        prepMinutes = 15,
                        cookMinutes = 30,
                        totalMinutes = 45,
                        cuisine = "Italian",
                        complexity = "easy",
                        complexityReason = "Simple mix",
                        spiceLevel = "mild",
                        dietType = "vegetarian",
                        contains = emptyList(),
                        kcalPerServing = 350,
                        makeAhead = false,
                        freezerFriendly = false,
                        ingredientGroups = listOf(
                            ExtractedIngredientGroup(
                                name = null,
                                items = listOf(
                                    ExtractedIngredient(
                                        quantity = 120.0,
                                        quantityMax = null,
                                        unit = "g",
                                        name = "flour",
                                        baseName = "flour",
                                        category = "baking",
                                        note = null,
                                        rawText = "1 cup flour"
                                    )
                                )
                            )
                        ),
                        steps = listOf(ExtractedStep(text = "Mix ingredients", timerSeconds = null)),
                        equipment = emptyList(),
                        consumables = emptyList(),
                        tags = tags,
                        newListSuggestions = emptyList(),
                        confidence = 0.9,
                        usage = LlmUsage(100, 50, 0, model = "claude-haiku-5-5")
                    )
                )
            }
        }
    }
}
