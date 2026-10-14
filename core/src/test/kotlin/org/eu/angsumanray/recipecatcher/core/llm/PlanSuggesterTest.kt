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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanSuggesterTest {

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
            id = 10L,
            title = "Palak Paneer",
            mainIngredients = listOf("paneer", "spinach"),
            favourite = true
        ),
        RecipeIndexEntry(
            id = 20L,
            title = "Dal Tadka",
            mainIngredients = listOf("lentils", "onion", "tomato")
        )
    )

    @Test
    fun testSuggestMealsValidatesSlotsAndRecipes() = runBlocking {
        val claudeResponse = """
        {
          "id": "msg_01",
          "type": "message",
          "role": "assistant",
          "content": [
            {
              "type": "text",
              "text": "```json\n[\n  {\"slot_id\": 1, \"recipe_id\": 10, \"portions\": 4, \"reason\": \"uses your spinach\"},\n  {\"slot_id\": 1, \"recipe_id\": 999, \"portions\": 4, \"reason\": \"invalid recipe\"},\n  {\"slot_id\": 999, \"recipe_id\": 10, \"portions\": 4, \"reason\": \"invalid slot\"}\n]\n```"
            }
          ],
          "usage": {
            "input_tokens": 400,
            "output_tokens": 80
          }
        }
        """.trimIndent()

        val client = createClient { _, _ -> HttpStatusCode.OK to claudeResponse }
        val suggester = PlanSuggester(
            httpClient = client,
            apiKeyProvider = { "api-key" }
        )

        val input = PlanSuggesterInput(
            emptySlots = listOf(
                EmptyPlanSlot(slotId = 1L, day = "Monday", slotName = "Dinner"),
                EmptyPlanSlot(slotId = 2L, day = "Tuesday", slotName = "Dinner")
            ),
            existingAssignments = listOf(
                ExistingPlanAssignment(slotId = 3L, recipeId = 5L, recipeTitle = "Pasta", day = "Wednesday", slotName = "Dinner")
            ),
            libraryRecipes = sampleLibrary,
            inStockIngredients = listOf("spinach", "paneer"),
            lowStockIngredients = listOf("tomatoes")
        )

        val result = suggester.suggestMeals(input)
        assertTrue(result.isSuccess)
        val planResult = result.getOrThrow()

        // Valid slot 1 + valid recipe 10 must be kept.
        // Invalid recipe 999 and invalid slot 999 must be dropped!
        assertEquals(1, planResult.suggestions.size)
        assertEquals(1L, planResult.suggestions[0].slotId)
        assertEquals(10L, planResult.suggestions[0].recipeId)
        assertEquals("uses your spinach", planResult.suggestions[0].reason)
        assertEquals(400, planResult.usage.inputTokens)
    }

    @Test
    fun testEmptySlotsReturnsEmptySuggestionsDirectly() = runBlocking {
        val suggester = PlanSuggester(
            httpClient = HttpClient(MockEngine) { engine { addHandler { respond("") } } },
            apiKeyProvider = { "key" }
        )

        val input = PlanSuggesterInput(
            emptySlots = emptyList(),
            libraryRecipes = sampleLibrary
        )

        val result = suggester.suggestMeals(input)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().suggestions.isEmpty())
    }
}
