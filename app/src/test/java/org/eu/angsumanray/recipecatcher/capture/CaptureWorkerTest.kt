package org.eu.angsumanray.recipecatcher.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
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
import org.eu.angsumanray.recipecatcher.core.llm.ImageRejectedException
import org.eu.angsumanray.recipecatcher.core.llm.NoRecipeFoundException
import org.eu.angsumanray.recipecatcher.core.llm.NewListSuggestion
import org.eu.angsumanray.recipecatcher.core.llm.RecipeExtractor
import org.eu.angsumanray.recipecatcher.core.llm.TransientApiException
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.StarterLists
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
import org.junit.Assert.assertNull
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
    private lateinit var imagePreprocessor: ImagePreprocessor

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
        imagePreprocessor = ImagePreprocessor(context, imageStorageManager)
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun createWorker(
        recipeId: Long,
        postFetcher: InstagramPostFetcher = InstagramPostFetcher(emptyList()),
        extractor: RecipeExtractor = unusedExtractor,
        runAttemptCount: Int = 0,
        webFetcher: org.eu.angsumanray.recipecatcher.core.web.WebRecipeFetcher = org.eu.angsumanray.recipecatcher.core.web.WebRecipeFetcher(
            object : org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher {
                override suspend fun get(url: String, headers: Map<String, String>) =
                    org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData(404, "")
            }
        ),
        ytFetcher: org.eu.angsumanray.recipecatcher.core.youtube.YouTubeFetcher = org.eu.angsumanray.recipecatcher.core.youtube.YouTubeFetcher(
            object : org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher {
                override suspend fun get(url: String, headers: Map<String, String>) =
                    org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData(404, "")
            }
        )
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
                    webFetcher,
                    ytFetcher,
                    imageStorageManager,
                    imagePreprocessor,
                    extractor,
                    settingsStore,
                    CommentFetcher(appContext, settingsStore)
                )
            }
        }

        return TestListenableWorkerBuilder<CaptureWorker>(context)
            .setInputData(workDataOf(CaptureWorker.KEY_RECIPE_ID to recipeId))
            .setWorkerFactory(factory)
            .setRunAttemptCount(runAttemptCount)
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

        var sentInput: ExtractionInput? = null
        val mockExtractor = object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> {
                sentInput = input
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
                        usage = LlmUsage(inputTokens = 1500, outputTokens = 600, cacheReadTokens = 300, model = "claude-haiku-5-5")
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
        assertEquals("claude-haiku-5-5", updated?.llmModel)

        // caption_raw keeps the hashtags; only the LLM input drops the trailing hashtag line.
        assertTrue(updated?.captionRaw.orEmpty().endsWith("#food"))
        assertEquals("Delicious Paneer Tikka recipe!\nIngredients: 250g paneer, curd, spices.", sentInput?.sourceText)
        assertTrue(sentInput!!.allowedEquipment.contains("kadai"))

        // Every list is matched within its own kind.
        val linked = database.recipeTagDao().getTagsForRecipe(recipeId).map { it.kind to it.name }.toSet()
        assertEquals(
            setOf(
                TagEntity.KIND_TAG to "paneer", TagEntity.KIND_TAG to "indian", TagEntity.KIND_TAG to "dinner",
                TagEntity.KIND_DIET to "dairy", TagEntity.KIND_EQUIPMENT to "kadai", TagEntity.KIND_CONSUMABLE to "skewers"
            ),
            linked
        )

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
                return Result.failure(NoRecipeFoundException("No recipe found", LlmUsage(200, 40, 0)))
            }
        }

        val worker = createWorker(recipeId, postFetcher, mockExtractor)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)

        val updated = recipeRepository.getRecipe(recipeId)
        assertEquals(RecipeEntity.STATUS_NO_RECIPE_FOUND, updated?.status)
        assertEquals("No recipe in caption", updated?.errorMessage)
        assertEquals(200, database.llmUsageDao().getAll().single().inputTokens)
    }

    private val unusedExtractor = object : RecipeExtractor {
        override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> = error("not expected")
    }

    @Test
    fun `web recipe capture with JSON-LD succeeds and transitions to needs_review`() = runBlocking {
        val recipeId = recipeRepository.insertPendingRecipe("web", "https://example.com/pizza", "https://example.com/pizza")
        val webHtml = """
            <html><head><script type="application/ld+json">
            {
              "@context": "https://schema.org",
              "@type": "Recipe",
              "name": "Quick Pizza",
              "recipeIngredient": ["Dough", "Cheese"],
              "recipeInstructions": ["Bake 15 min"]
            }
            </script></head><body></body></html>
        """.trimIndent()

        val webFetcher = org.eu.angsumanray.recipecatcher.core.web.WebRecipeFetcher(object : org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>) =
                org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData(200, webHtml)
        })

        val extractor = object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> =
                Result.success(
                    ExtractedRecipe(
                        isRecipe = true,
                        title = "Quick Pizza",
                        description = "Yummy pizza",
                        servings = 2,
                        prepMinutes = 10,
                        cookMinutes = 15,
                        totalMinutes = 25,
                        cuisine = "Italian",
                        complexity = "easy",
                        complexityReason = "Quick to make",
                        spiceLevel = "mild",
                        dietType = "vegetarian",
                        contains = emptyList(),
                        kcalPerServing = 400,
                        makeAhead = false,
                        freezerFriendly = false,
                        ingredientGroups = listOf(
                            ExtractedIngredientGroup(
                                null,
                                listOf(
                                    ExtractedIngredient(250.0, null, "g", "pizza dough", "dough", "pantry", null, "250g dough"),
                                    ExtractedIngredient(100.0, null, "g", "cheese", "cheese", "dairy", null, "100g cheese")
                                )
                            )
                        ),
                        steps = listOf(ExtractedStep("Bake for 15 minutes", 900)),
                        equipment = emptyList(),
                        consumables = emptyList(),
                        tags = emptyList(),
                        newListSuggestions = emptyList(),
                        confidence = 0.95,
                        usage = LlmUsage(100, 50, 0)
                    )
                )
        }

        val result = createWorker(recipeId = recipeId, extractor = extractor, webFetcher = webFetcher).doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val updated = recipeRepository.getRecipe(recipeId)
        assertEquals(RecipeEntity.STATUS_NEEDS_REVIEW, updated?.status)
        assertEquals("Quick Pizza", updated?.title)
    }

    @Test
    fun `web recipe capture direct mapping fallback succeeds when LLM fails but JSON-LD exists`() = runBlocking {
        val recipeId = recipeRepository.insertPendingRecipe("web", "https://example.com/pasta", "https://example.com/pasta")
        val webHtml = """
            <html><head><script type="application/ld+json">
            {
              "@context": "https://schema.org",
              "@type": "Recipe",
              "name": "Direct Pasta",
              "recipeYield": 4,
              "prepTime": "PT10M",
              "cookTime": "PT15M",
              "recipeIngredient": ["200g pasta", "Tomato sauce"],
              "recipeInstructions": ["Boil pasta", "Mix sauce"]
            }
            </script></head><body></body></html>
        """.trimIndent()

        val webFetcher = org.eu.angsumanray.recipecatcher.core.web.WebRecipeFetcher(object : org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>) =
                org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData(200, webHtml)
        })

        // Failing extractor (e.g. invalid key or API error)
        val failingExtractor = object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> =
                Result.failure(TransientApiException("500 internal error", 500))
        }

        val result = createWorker(recipeId = recipeId, extractor = failingExtractor, webFetcher = webFetcher).doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val updated = recipeRepository.getRecipe(recipeId)
        assertEquals(RecipeEntity.STATUS_NEEDS_REVIEW, updated?.status)
        assertEquals("Direct Pasta", updated?.title)
        assertEquals(10, updated?.prepMin)
        assertEquals(15, updated?.cookMin)
        assertEquals(25, updated?.totalMin)
        assertEquals(4, updated?.servings)
        val ingredients = recipeRepository.getIngredients(recipeId)
        assertEquals(2, ingredients.size)
        assertEquals("200g pasta", ingredients[0].rawText)
    }

    @Test
    fun `blocked web fetch marks failed_fetch without retrying`() = runBlocking {
        val recipeId = recipeRepository.insertPendingRecipe("web", "https://blocked.example/r", "https://blocked.example/r")
        val webFetcher = org.eu.angsumanray.recipecatcher.core.web.WebRecipeFetcher(object : org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>) =
                org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData(403, "Forbidden")
        })

        val result = createWorker(recipeId = recipeId, webFetcher = webFetcher).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        val row = recipeRepository.getRecipe(recipeId)
        assertEquals(RecipeEntity.STATUS_FAILED_FETCH, row?.status)
        assertTrue(row?.errorMessage.orEmpty().startsWith("Blocked"))
    }

    @Test
    fun `youtube recipe capture succeeds and transitions to needs_review`() = runBlocking {
        val recipeId = recipeRepository.insertPendingRecipe("youtube", "yt_video_1", "https://www.youtube.com/watch?v=yt_video_1")
        val ytHtml = """
            <html><head><script>
            var ytInitialPlayerResponse = {
                "videoDetails": {
                    "videoId": "yt_video_1",
                    "title": "5 Min Garlic Butter Noodles",
                    "author": "Chef Noodle",
                    "shortDescription": "Ingredients: 200g noodles, 2 tbsp butter, 3 garlic cloves. Boil and toss!"
                }
            };
            </script></head><body></body></html>
        """.trimIndent()

        val ytFetcher = org.eu.angsumanray.recipecatcher.core.youtube.YouTubeFetcher(object : org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>) =
                org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData(200, ytHtml)
        })

        val extractor = object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> =
                Result.success(
                    ExtractedRecipe(
                        isRecipe = true,
                        title = "Garlic Butter Noodles",
                        description = "Noodles in butter",
                        servings = 1,
                        prepMinutes = 2,
                        cookMinutes = 5,
                        totalMinutes = 7,
                        cuisine = "Asian",
                        complexity = "easy",
                        complexityReason = "Super fast",
                        spiceLevel = "mild",
                        dietType = "vegetarian",
                        contains = emptyList(),
                        kcalPerServing = 350,
                        makeAhead = false,
                        freezerFriendly = false,
                        ingredientGroups = listOf(
                            ExtractedIngredientGroup(
                                null,
                                listOf(
                                    ExtractedIngredient(200.0, null, "g", "noodles", "noodles", "pantry", null, "200g noodles")
                                )
                            )
                        ),
                        steps = listOf(ExtractedStep("Boil noodles and toss with butter", 300)),
                        equipment = emptyList(),
                        consumables = emptyList(),
                        tags = emptyList(),
                        newListSuggestions = emptyList(),
                        confidence = 0.9,
                        usage = LlmUsage(80, 40, 0)
                    )
                )
        }

        val result = createWorker(recipeId = recipeId, extractor = extractor, ytFetcher = ytFetcher).doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val updated = recipeRepository.getRecipe(recipeId)
        assertEquals(RecipeEntity.STATUS_NEEDS_REVIEW, updated?.status)
        assertEquals("Garlic Butter Noodles", updated?.title)
    }

    @Test
    fun `youtube recipe capture falls back to linked page in description when no recipe in video`() = runBlocking {
        val recipeId = recipeRepository.insertPendingRecipe("youtube", "yt_link_1", "https://www.youtube.com/watch?v=yt_link_1")
        val ytHtml = """
            <html><head><script>
            var ytInitialPlayerResponse = {
                "videoDetails": {
                    "videoId": "yt_link_1",
                    "title": "My Favorite Curry Vlog",
                    "author": "Vlog Chef",
                    "shortDescription": "Check out my vlog! Written recipe at https://myrecipeblog.com/curry and follow me on https://instagram.com/chef"
                }
            };
            </script></head><body></body></html>
        """.trimIndent()

        val webHtml = """
            <html><head><script type="application/ld+json">
            {
              "@context": "https://schema.org",
              "@type": "Recipe",
              "name": "Linked Chicken Curry",
              "recipeIngredient": ["Chicken", "Curry powder"],
              "recipeInstructions": ["Cook chicken with curry powder"]
            }
            </script></head><body></body></html>
        """.trimIndent()

        val ytFetcher = org.eu.angsumanray.recipecatcher.core.youtube.YouTubeFetcher(object : org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>) =
                org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData(200, ytHtml)
        })

        val webFetcher = org.eu.angsumanray.recipecatcher.core.web.WebRecipeFetcher(object : org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>) =
                org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData(200, webHtml)
        })

        val extractor = object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> {
                // If input is YouTube description (no recipe): return isRecipe = false
                if (input.sourceType == SourceType.YOUTUBE) {
                    return Result.failure(NoRecipeFoundException("Vlog only"))
                }
                // When called with linked web page:
                return Result.success(
                    ExtractedRecipe(
                        isRecipe = true,
                        title = "Linked Chicken Curry",
                        description = "Tasty curry from blog",
                        servings = 4,
                        prepMinutes = 15,
                        cookMinutes = 30,
                        totalMinutes = 45,
                        cuisine = "Indian",
                        complexity = "medium",
                        complexityReason = "Simmering",
                        spiceLevel = "medium",
                        dietType = "non-veg",
                        contains = emptyList(),
                        kcalPerServing = 500,
                        makeAhead = true,
                        freezerFriendly = true,
                        ingredientGroups = listOf(
                            ExtractedIngredientGroup(
                                null,
                                listOf(ExtractedIngredient(500.0, null, "g", "chicken", "chicken", "meat", null, "500g chicken"))
                            )
                        ),
                        steps = listOf(ExtractedStep("Cook chicken", null)),
                        equipment = emptyList(),
                        consumables = emptyList(),
                        tags = emptyList(),
                        newListSuggestions = emptyList(),
                        confidence = 0.95,
                        usage = LlmUsage(120, 60, 0)
                    )
                )
            }
        }

        val result = createWorker(
            recipeId = recipeId,
            extractor = extractor,
            webFetcher = webFetcher,
            ytFetcher = ytFetcher
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val updated = recipeRepository.getRecipe(recipeId)
        assertEquals(RecipeEntity.STATUS_NEEDS_REVIEW, updated?.status)
        assertEquals("Linked Chicken Curry", updated?.title)
        assertEquals("yt_link_1", updated?.sourceKey) // Preserved YouTube sourceKey!
        assertEquals("https://www.youtube.com/watch?v=yt_link_1", updated?.sourceUrl)
        assertTrue(updated?.captionRaw.orEmpty().contains("Linked Chicken Curry"))
    }

    @Test
    fun `fetch failure retries then marks failed_fetch on the last attempt`() = runBlocking {
        val recipeId = recipeRepository.insertPendingRecipe("instagram", "NOPE_1")
        val emptyFetcher = InstagramPostFetcher(emptyList())

        assertEquals(ListenableWorker.Result.retry(), createWorker(recipeId, emptyFetcher, unusedExtractor, 0).doWork())
        assertEquals(RecipeEntity.STATUS_PENDING_FETCH, recipeRepository.getRecipe(recipeId)?.status)

        assertEquals(ListenableWorker.Result.failure(), createWorker(recipeId, emptyFetcher, unusedExtractor, 2).doWork())
        val failed = recipeRepository.getRecipe(recipeId)
        assertEquals(RecipeEntity.STATUS_FAILED_FETCH, failed?.status)
        assertEquals("Couldn't read this post", failed?.errorMessage)
    }

    @Test
    fun `transient API errors retry and keep the fetched caption`() = runBlocking {
        val recipeId = recipeRepository.insertPendingRecipe("instagram", "BUSY_1")
        val fetcher = InstagramPostFetcher(listOf(object : PostFetchStrategy {
            override val name: String = "Mock"
            override suspend fun fetch(shortcode: String) = PostData("Dal: 1 cup lentils, boil 20 min", "dalchef", null)
        }))
        val busy = object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> =
                Result.failure(TransientApiException("overloaded", 529))
        }

        assertEquals(ListenableWorker.Result.retry(), createWorker(recipeId, fetcher, busy).doWork())
        val row = recipeRepository.getRecipe(recipeId)
        assertEquals(RecipeEntity.STATUS_PENDING_EXTRACT, row?.status)
        assertEquals("Dal: 1 cup lentils, boil 20 min", row?.captionRaw)
        assertNull(row?.errorMessage)
    }

    @Test
    fun `missing API key leaves the row pending with a prompt`() = runBlocking {
        settingsStore.saveApiKey("")
        val recipeId = recipeRepository.insertPendingRecipe("instagram", "NOKEY_1")
        val fetcher = InstagramPostFetcher(listOf(object : PostFetchStrategy {
            override val name: String = "Mock"
            override suspend fun fetch(shortcode: String) = PostData("Caption", null, null)
        }))

        assertEquals(ListenableWorker.Result.failure(), createWorker(recipeId, fetcher, unusedExtractor).doWork())
        val row = recipeRepository.getRecipe(recipeId)
        assertEquals(RecipeEntity.STATUS_PENDING_EXTRACT, row?.status)
        assertEquals("Add Anthropic API key in Settings", row?.errorMessage)
    }

    private fun createSampleJpeg(width: Int = 400, height: Int = 400): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.GREEN)
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        bitmap.recycle()
        return bos.toByteArray()
    }

    private fun createExtractedRecipe(
        isRecipe: Boolean = true,
        title: String? = "Test Recipe",
        description: String? = "Test description",
        servings: Int? = 2,
        prepMinutes: Int? = null,
        cookMinutes: Int? = null,
        totalMinutes: Int? = null,
        cuisine: String? = null,
        complexity: String? = null,
        complexityReason: String? = null,
        spiceLevel: String? = null,
        dietType: String? = null,
        contains: List<String> = emptyList(),
        kcalPerServing: Int? = null,
        makeAhead: Boolean = false,
        freezerFriendly: Boolean = false,
        ingredientGroups: List<ExtractedIngredientGroup> = emptyList(),
        steps: List<ExtractedStep> = emptyList(),
        equipment: List<String> = emptyList(),
        consumables: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        newListSuggestions: List<NewListSuggestion> = emptyList(),
        confidence: Double = 0.95,
        usage: LlmUsage = LlmUsage(inputTokens = 100, outputTokens = 50, cacheReadTokens = 0, model = "claude-haiku-5-5")
    ): ExtractedRecipe = ExtractedRecipe(
        isRecipe = isRecipe,
        title = title,
        description = description,
        servings = servings,
        prepMinutes = prepMinutes,
        cookMinutes = cookMinutes,
        totalMinutes = totalMinutes,
        cuisine = cuisine,
        complexity = complexity,
        complexityReason = complexityReason,
        spiceLevel = spiceLevel,
        dietType = dietType,
        contains = contains,
        kcalPerServing = kcalPerServing,
        makeAhead = makeAhead,
        freezerFriendly = freezerFriendly,
        ingredientGroups = ingredientGroups,
        steps = steps,
        equipment = equipment,
        consumables = consumables,
        tags = tags,
        newListSuggestions = newListSuggestions,
        confidence = confidence,
        usage = usage
    )

    private fun createExtractedIngredient(
        name: String,
        rawText: String,
        quantity: Double? = null,
        quantityMax: Double? = null,
        unit: String? = null,
        baseName: String = name.lowercase(),
        category: String = "pantry",
        note: String? = null
    ): ExtractedIngredient = ExtractedIngredient(
        quantity = quantity,
        quantityMax = quantityMax,
        unit = unit,
        name = name,
        baseName = baseName,
        category = category,
        note = note,
        rawText = rawText
    )

    private fun createExtractedStep(
        text: String,
        timerSeconds: Int? = null
    ): ExtractedStep = ExtractedStep(text = text, timerSeconds = timerSeconds)

    @Test
    fun `CaptureWorker extracts SourceType_IMAGE with images from disk and sets cover photo`() = runBlocking {
        val dir = File(context.cacheDir, "test_images_1").apply { mkdirs() }
        val img1 = File(dir, "img_1.jpg").apply { writeBytes(createSampleJpeg(300, 300)) }
        val img2 = File(dir, "img_2.jpg").apply { writeBytes(createSampleJpeg(300, 300)) }

        val recipeId = recipeRepository.insertPendingImageRecipe("image_test_key_1", dir.absolutePath)

        var capturedImages = emptyList<String>()
        val mockExtractor = object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> {
                assertEquals(SourceType.IMAGE, input.sourceType)
                capturedImages = input.sourceImages
                return Result.success(
                    createExtractedRecipe(
                        isRecipe = true,
                        title = "Cookbook Dal Tadka",
                        description = "Classic dal",
                        servings = 4,
                        ingredientGroups = listOf(
                            ExtractedIngredientGroup(
                                name = null,
                                items = listOf(createExtractedIngredient(name = "Dal", rawText = "1 cup dal", quantity = 1.0, unit = "cup"))
                            )
                        ),
                        steps = listOf(createExtractedStep(text = "Boil dal"))
                    )
                )
            }
        }

        val result = createWorker(recipeId, extractor = mockExtractor).doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val updated = recipeRepository.getRecipe(recipeId)
        assertNotNull(updated)
        assertEquals(RecipeEntity.STATUS_NEEDS_REVIEW, updated!!.status)
        assertEquals("Cookbook Dal Tadka", updated.title)
        assertEquals("images/$recipeId.jpg", updated.imagePath)
        assertEquals("images/$recipeId.jpg", updated.coverPhotoPath)
        assertEquals(2, capturedImages.size)

        val coverFile = imageStorageManager.getRecipeImageFile(recipeId)
        assertTrue(coverFile.exists())
        assertTrue(coverFile.length() > 0)

        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `CaptureWorker falls back to YouTube caption transcript when description is empty`() = runBlocking {
        val recipeId = recipeRepository.insertPendingRecipe("youtube", "yt_empty_desc_1")

        val trackUrl = "https://www.youtube.com/api/timedtext?v=123"
        val fakeYtFetcher = object : org.eu.angsumanray.recipecatcher.core.youtube.YouTubeFetcher(
            object : org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher {
                override suspend fun get(url: String, headers: Map<String, String>) =
                    org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData(404, "")
            }
        ) {
            override suspend fun fetch(videoId: String) = org.eu.angsumanray.recipecatcher.core.youtube.YouTubeData(
                videoId = videoId,
                title = "Quick Omelette",
                author = "Chef Mike",
                description = "",
                thumbnailUrl = "",
                isPlayerResponse = true,
                preferredCaptionTrackUrl = trackUrl
            )

            override suspend fun fetchCaptionTranscript(trackUrl: String): String =
                "Break two eggs in a bowl. Whisk thoroughly with salt. Pour into hot buttered pan and fold."
        }

        var capturedSourceText = ""
        val mockExtractor = object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> {
                assertEquals(SourceType.YOUTUBE, input.sourceType)
                capturedSourceText = input.sourceText
                return Result.success(
                    createExtractedRecipe(
                        isRecipe = true,
                        title = "Quick Omelette",
                        description = "Whisked eggs",
                        servings = 1,
                        ingredientGroups = listOf(
                            ExtractedIngredientGroup(
                                name = null,
                                items = listOf(createExtractedIngredient(name = "Eggs", rawText = "2 eggs", quantity = 2.0))
                            )
                        ),
                        steps = listOf(createExtractedStep(text = "Whisk and fold"))
                    )
                )
            }
        }

        val result = createWorker(recipeId, ytFetcher = fakeYtFetcher, extractor = mockExtractor).doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val updated = recipeRepository.getRecipe(recipeId)
        assertNotNull(updated)
        assertEquals(RecipeEntity.STATUS_NEEDS_REVIEW, updated!!.status)
        assertEquals("Quick Omelette", updated.title)
        assertTrue(capturedSourceText.contains("Break two eggs"))
    }

    @Test
    fun `CaptureWorker falls back to YouTube caption transcript when description has no recipe`() = runBlocking {
        val recipeId = recipeRepository.insertPendingRecipe("youtube", "yt_no_recipe_desc")

        val trackUrl = "https://www.youtube.com/api/timedtext?v=456"
        val fakeYtFetcher = object : org.eu.angsumanray.recipecatcher.core.youtube.YouTubeFetcher(
            object : org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher {
                override suspend fun get(url: String, headers: Map<String, String>) =
                    org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData(404, "")
            }
        ) {
            override suspend fun fetch(videoId: String) = org.eu.angsumanray.recipecatcher.core.youtube.YouTubeData(
                videoId = videoId,
                title = "Fried Rice",
                author = "Chef Linda",
                description = "Don't forget to like and subscribe! Social links below.",
                thumbnailUrl = "",
                isPlayerResponse = true,
                preferredCaptionTrackUrl = trackUrl
            )

            override suspend fun fetchCaptionTranscript(trackUrl: String): String =
                "Heat a wok with 1 tbsp sesame oil. Toss in cooked cold rice, 2 sliced spring onions, and 1 tbsp soy sauce."
        }

        var callCount = 0
        val mockExtractor = object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> {
                callCount++
                if (callCount == 1) {
                    // First call with YouTube description text returns no recipe found
                    return Result.failure(NoRecipeFoundException("No recipe in description"))
                }
                // Second call with caption transcript succeeds
                assertTrue(input.sourceText.contains("Heat a wok"))
                return Result.success(
                    createExtractedRecipe(
                        isRecipe = true,
                        title = "Fried Rice",
                        description = "Wok fried rice",
                        servings = 2,
                        ingredientGroups = listOf(
                            ExtractedIngredientGroup(
                                name = null,
                                items = listOf(createExtractedIngredient(name = "Rice", rawText = "cooked cold rice"))
                            )
                        ),
                        steps = listOf(createExtractedStep(text = "Toss in wok"))
                    )
                )
            }
        }

        val result = createWorker(recipeId, ytFetcher = fakeYtFetcher, extractor = mockExtractor).doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val updated = recipeRepository.getRecipe(recipeId)
        assertNotNull(updated)
        assertEquals(RecipeEntity.STATUS_NEEDS_REVIEW, updated!!.status)
        assertEquals("Fried Rice", updated.title)
    }

    @Test
    fun `CaptureWorker extracts Instagram carousel slides when caption lacks recipe quantities`() = runBlocking {
        val recipeId = recipeRepository.insertPendingRecipe("instagram", "carousel_shortcode_1")

        // Pre-create slide file in cacheDir
        val slidesDir = File(context.cacheDir, "slides/$recipeId").apply { mkdirs() }
        val slide2 = File(slidesDir, "slide_2.jpg").apply { writeBytes(createSampleJpeg(300, 300)) }

        val mockStrategy = object : PostFetchStrategy {
            override val name: String = "Mock"
            override suspend fun fetch(shortcode: String): PostData {
                return PostData(
                    caption = "Swipe to see the full recipe in slides! #cooking #dinner",
                    author = "homechef",
                    imageUrl = null,
                    slideImageUrls = listOf("https://example.com/s2.jpg")
                )
            }
        }

        var visionImagesProvided = false
        val mockExtractor = object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> {
                if (input.sourceImages.isNotEmpty()) {
                    visionImagesProvided = true
                }
                return Result.success(
                    createExtractedRecipe(
                        isRecipe = true,
                        title = "Slide Recipe Dish",
                        description = "From carousel slides",
                        servings = 3
                    )
                )
            }
        }

        val result = createWorker(
            recipeId,
            postFetcher = InstagramPostFetcher(listOf(mockStrategy)),
            extractor = mockExtractor
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(visionImagesProvided)

        val updated = recipeRepository.getRecipe(recipeId)
        assertNotNull(updated)
        assertEquals(RecipeEntity.STATUS_NEEDS_REVIEW, updated!!.status)
        assertEquals("Slide Recipe Dish", updated.title)

        slidesDir.deleteRecursively()
        Unit
    }

    @Test
    fun `CaptureWorker sends at most five shared photos and clears the capture cache on success`() = runBlocking {
        val sourceKey = "image_cap_test"
        val dir = File(context.cacheDir, "image_captures/$sourceKey").apply { mkdirs() }
        repeat(7) { File(dir, "img_${it + 1}.jpg").writeBytes(createSampleJpeg(200, 200)) }
        val recipeId = recipeRepository.insertPendingImageRecipe(sourceKey, dir.absolutePath)

        var imageCount = 0
        val mockExtractor = object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> {
                imageCount = input.sourceImages.size
                return Result.success(createExtractedRecipe(title = "Scanned Dish"))
            }
        }

        val result = createWorker(recipeId, extractor = mockExtractor).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(5, imageCount)
        assertFalse(dir.exists())
    }

    @Test
    fun `CaptureWorker fails without retry when the API rejects the image`() = runBlocking {
        val dir = File(context.cacheDir, "image_captures/image_rejected").apply { mkdirs() }
        File(dir, "img_1.jpg").writeBytes(createSampleJpeg(200, 200))
        val recipeId = recipeRepository.insertPendingImageRecipe("image_rejected", dir.absolutePath)

        val rejectingExtractor = object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> =
                Result.failure(ImageRejectedException("Image rejected: HTTP 413"))
        }

        val result = createWorker(recipeId, extractor = rejectingExtractor).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        val updated = recipeRepository.getRecipe(recipeId)
        assertEquals(RecipeEntity.STATUS_FAILED_EXTRACT, updated?.status)
        assertEquals("Image processing failed or file too large", updated?.errorMessage)
        assertTrue(dir.exists())
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `CaptureWorker does not re-extract a transcript already used as the source text`() = runBlocking {
        val recipeId = recipeRepository.insertPendingRecipe("youtube", "yt_transcript_once")
        val transcript = "Today we talk about knives and kitchen tours."

        val fakeYtFetcher = object : org.eu.angsumanray.recipecatcher.core.youtube.YouTubeFetcher(
            object : org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher {
                override suspend fun get(url: String, headers: Map<String, String>) =
                    org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData(404, "")
            }
        ) {
            override suspend fun fetch(urlOrId: String) = org.eu.angsumanray.recipecatcher.core.youtube.YouTubeData(
                videoId = urlOrId,
                title = "Kitchen Tour",
                author = "Chef",
                description = "",
                thumbnailUrl = "",
                isPlayerResponse = true,
                preferredCaptionTrackUrl = "https://www.youtube.com/api/timedtext?v=789"
            )

            override suspend fun fetchCaptionTranscript(trackUrl: String): String = transcript
        }

        var calls = 0
        val mockExtractor = object : RecipeExtractor {
            override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> {
                calls++
                return Result.failure(NoRecipeFoundException("No recipe"))
            }
        }

        val result = createWorker(recipeId, ytFetcher = fakeYtFetcher, extractor = mockExtractor).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(1, calls)
        val updated = recipeRepository.getRecipe(recipeId)
        assertEquals(RecipeEntity.STATUS_NO_RECIPE_FOUND, updated?.status)
        assertEquals("No recipe in description or captions", updated?.errorMessage)
    }
}
