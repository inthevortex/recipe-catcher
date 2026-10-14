package org.eu.angsumanray.recipecatcher.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.core.model.ChatMessage
import org.eu.angsumanray.recipecatcher.core.ports.SearchResult
import org.eu.angsumanray.recipecatcher.core.ports.WebSearchProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPlannerTest {

    private fun createClient(handler: (request: HttpRequestData, body: String) -> Pair<HttpStatusCode, String>): HttpClient {
        return HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            engine {
                addHandler { request ->
                    val body = when (val content = request.body) {
                        is TextContent -> content.text
                        else -> ""
                    }
                    val (status, responseBody) = handler(request, body)
                    respond(
                        content = responseBody,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
    }

    private val sampleLibrary = listOf(
        RecipeIndexEntry(
            id = 1L,
            title = "Palak Paneer",
            tags = listOf("indian", "curry", "vegetarian"),
            mainIngredients = listOf("paneer", "spinach", "onion", "garlic"),
            totalMinutes = 30,
            complexity = "easy",
            favourite = true
        ),
        RecipeIndexEntry(
            id = 2L,
            title = "Chicken Biryani",
            tags = listOf("indian", "rice"),
            mainIngredients = listOf("chicken", "rice", "spices"),
            totalMinutes = 60,
            complexity = "hard"
        ),
        RecipeIndexEntry(
            id = 3L,
            title = "Tomato Soup",
            tags = listOf("soup", "quick"),
            mainIngredients = listOf("tomatoes", "cream", "basil"),
            totalMinutes = 20,
            complexity = "easy"
        )
    )

    @Test
    fun testLibraryCallValidatesRecipeIdsAndCapsSuggestions() = runBlocking {
        val anthropicResponse = """
        {
          "id": "msg_01",
          "type": "message",
          "role": "assistant",
          "content": [
            {
              "type": "text",
              "text": "{\n  \"reply\": \"Palak Paneer is great for tonight.\",\n  \"suggestions\": [\n    {\"recipe_id\": 1, \"reason\": \"30 min, uses spinach\", \"missing\": []},\n    {\"recipe_id\": 999, \"reason\": \"Fake recipe\", \"missing\": []}\n  ],\n  \"follow_up_question\": \"Do you have garlic?\"\n}"
            }
          ],
          "usage": {
            "input_tokens": 500,
            "output_tokens": 120,
            "cache_read_input_tokens": 100
          }
        }
        """.trimIndent()

        val client = createClient { _, _ -> HttpStatusCode.OK to anthropicResponse }
        val planner = ChatPlanner(
            httpClient = client,
            apiKeyProvider = { "test-api-key" }
        )

        val result = planner.planChat(
            userMessage = "What can I cook with spinach?",
            libraryRecipes = sampleLibrary,
            includeWeb = false
        )

        assertTrue(result.isSuccess)
        val plan = result.getOrThrow()
        assertEquals("Palak Paneer is great for tonight.", plan.reply)
        assertEquals("Do you have garlic?", plan.followUpQuestion)
        // Invalid ID 999 should be dropped!
        assertEquals(1, plan.suggestions.size)
        assertEquals(1L, plan.suggestions[0].recipeId)
        assertEquals(500, plan.usage.inputTokens)
    }

    @Test
    fun testPrefilterRecipesAboveThreshold() {
        val planner = ChatPlanner(
            httpClient = HttpClient(MockEngine) { engine { addHandler { respond("") } } },
            apiKeyProvider = { "key" }
        )

        val recipes = (1..350).map { i ->
            RecipeIndexEntry(
                id = i.toLong(),
                title = if (i == 42) "Spicy Paneer Bhurji" else "Dish number $i",
                tags = if (i == 42) listOf("paneer", "spicy") else listOf("generic"),
                mainIngredients = if (i == 42) listOf("paneer", "green chili") else listOf("ingredient-$i")
            )
        }

        val filtered = planner.prefilterRecipes(recipes, "I want something with paneer", cap = 150)
        assertEquals(150, filtered.size)
        assertTrue(filtered.any { it.id == 42L })
        // Recipe 42 should score highest and be at the top!
        assertEquals(42L, filtered[0].id)
    }

    @Test
    fun testWebCallWithWebSearchProviderKeepsStrictUrls() = runBlocking {
        val libraryResponse = """
        {
          "id": "msg_01",
          "type": "message",
          "role": "assistant",
          "content": [
            {
              "type": "text",
              "text": "{\n  \"reply\": \"Here are ideas.\",\n  \"suggestions\": [\n    {\"recipe_id\": 1, \"reason\": \"Quick paneer\", \"missing\": []}\n  ],\n  \"follow_up_question\": \"\"\n}"
            }
          ],
          "usage": {"input_tokens": 400, "output_tokens": 100}
        }
        """.trimIndent()

        val webClaudeResponse = """
        {
          "id": "msg_02",
          "type": "message",
          "role": "assistant",
          "content": [
            {
              "type": "text",
              "text": "```json\n[\n  {\"title\": \"Paneer Tikka\", \"url\": \"https://example.com/paneer-tikka\", \"site\": \"example.com\", \"reason\": \"Grilled spiced paneer\"},\n  {\"title\": \"Hallucinated\", \"url\": \"https://hallucinated.com/fake\", \"site\": \"fake.com\", \"reason\": \"Fake\"}\n]\n```"
            }
          ],
          "usage": {"input_tokens": 300, "output_tokens": 80}
        }
        """.trimIndent()

        var callCount = 0
        val client = createClient { _, body ->
            callCount++
            if (body.contains("Candidate pages must contain a full written recipe") || body.contains("Search results for")) {
                HttpStatusCode.OK to webClaudeResponse
            } else {
                HttpStatusCode.OK to libraryResponse
            }
        }

        val fakeWebProvider = object : WebSearchProvider {
            override suspend fun search(query: String, maxResults: Int): List<SearchResult> {
                return listOf(
                    SearchResult(
                        title = "Paneer Tikka Recipe",
                        url = "https://example.com/paneer-tikka",
                        snippet = "Delicious grilled paneer recipe",
                        site = "example.com"
                    )
                )
            }
        }

        val planner = ChatPlanner(
            httpClient = client,
            apiKeyProvider = { "test-api-key" },
            webSearchProvider = fakeWebProvider
        )

        val result = planner.planChat(
            userMessage = "paneer recipe",
            libraryRecipes = sampleLibrary,
            includeWeb = true,
            maxWebSearches = 1
        )

        assertTrue(result.isSuccess)
        val plan = result.getOrThrow()
        assertEquals(1, plan.suggestions.size)
        // Strictly only the URL present in search results should be kept! Hallucinated URL must be dropped.
        assertEquals(1, plan.webSuggestions.size)
        assertEquals("https://example.com/paneer-tikka", plan.webSuggestions[0].url)
        assertEquals("Paneer Tikka", plan.webSuggestions[0].title)
    }

    @Test
    fun testFindWebCandidates() = runBlocking {
        val webClaudeResponse = """
        {
          "id": "msg_02",
          "type": "message",
          "role": "assistant",
          "content": [
            {
              "type": "text",
              "text": "```json\n[\n  {\"title\": \"Bengali Shorshe Ilish\", \"url\": \"https://bongcook.com/ilish\", \"site\": \"bongcook.com\", \"reason\": \"Traditional mustard fish\"}\n]\n```"
            }
          ],
          "usage": {"input_tokens": 300, "output_tokens": 80}
        }
        """.trimIndent()

        val client = createClient { _, _ -> HttpStatusCode.OK to webClaudeResponse }
        val fakeWebProvider = object : WebSearchProvider {
            override suspend fun search(query: String, maxResults: Int): List<SearchResult> {
                return listOf(
                    SearchResult(
                        title = "Bengali Shorshe Ilish",
                        url = "https://bongcook.com/ilish",
                        snippet = "Authentic recipe",
                        site = "bongcook.com"
                    )
                )
            }
        }

        val planner = ChatPlanner(
            httpClient = client,
            apiKeyProvider = { "key" },
            webSearchProvider = fakeWebProvider
        )

        val result = planner.findWebCandidates("Shorshe Ilish", "traditional mustard")
        assertTrue(result.isSuccess)
        val candidates = result.getOrThrow()
        assertEquals(1, candidates.size)
        assertEquals("https://bongcook.com/ilish", candidates[0].url)
    }
}
