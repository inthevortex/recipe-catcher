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
    fun testValidateDedupesClampsPortionsAndCapsPerSlot() {
        val raw = listOf(
            ProposedSlotAssignment(slotId = 1, recipeId = 10, portions = 0),
            ProposedSlotAssignment(slotId = 1, recipeId = 10, portions = 4),
            ProposedSlotAssignment(slotId = 1, recipeId = 11, portions = 500),
            ProposedSlotAssignment(slotId = 1, recipeId = 12),
            ProposedSlotAssignment(slotId = 1, recipeId = 13),
            ProposedSlotAssignment(slotId = 2, recipeId = 10)
        )

        val validated = PlanSuggester.validate(raw, validSlotIds = setOf(1L, 2L), validRecipeIds = setOf(10L, 11L, 12L, 13L))

        assertEquals(listOf(10L, 11L, 12L), validated.filter { it.slotId == 1L }.map { it.recipeId })
        assertEquals(1, validated[0].portions)
        assertEquals(PlanSuggester.MAX_PORTIONS, validated[1].portions)
        assertEquals(1, validated.count { it.slotId == 2L })
    }

    @Test
    fun testDefaultSelectionPicksOnePerSlotAndAvoidsRepeats() {
        val suggestions = listOf(
            ProposedSlotAssignment(slotId = 1, recipeId = 10),
            ProposedSlotAssignment(slotId = 1, recipeId = 11),
            ProposedSlotAssignment(slotId = 2, recipeId = 10),
            ProposedSlotAssignment(slotId = 2, recipeId = 12),
            ProposedSlotAssignment(slotId = 3, recipeId = 5),
            ProposedSlotAssignment(slotId = 3, recipeId = 13),
            ProposedSlotAssignment(slotId = 4, recipeId = 10)
        )

        val picks = PlanSuggester.defaultSelection(
            suggestions,
            slotOrder = listOf(1L, 2L, 3L, 4L),
            alreadyPlannedRecipeIds = setOf(5L)
        )

        // Slot 2 skips 10 (picked for slot 1), slot 3 skips 5 (already planned); slot 4 has no fresh option.
        assertEquals(listOf(1L to 10L, 2L to 12L, 3L to 13L, 4L to 10L), picks.map { it.slotId to it.recipeId })
    }

    @Test
    fun testDefaultSelectionAvoidsRecipesWithLeftovers() {
        val suggestions = listOf(
            ProposedSlotAssignment(slotId = 1, recipeId = 20),
            ProposedSlotAssignment(slotId = 1, recipeId = 21)
        )

        val picks = PlanSuggester.defaultSelection(suggestions, listOf(1L), leftoverRecipeIds = setOf(20L))

        assertEquals(listOf(21L), picks.map { it.recipeId })
    }

    @Test
    fun testHttpErrorFailsWithTypedException() = runBlocking {
        val suggester = PlanSuggester(createClient { _, _ -> HttpStatusCode.TooManyRequests to "{}" }, { "key" })

        val result = suggester.suggestMeals(
            PlanSuggesterInput(
                emptySlots = listOf(EmptyPlanSlot(slotId = 1L, day = "Monday", slotName = "Dinner")),
                libraryRecipes = sampleLibrary
            )
        )

        assertTrue(result.exceptionOrNull() is TransientApiException)
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

    @Test
    fun testParseSuggestionsHandlesObjectWrappingAndStringIds() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        // Object with "suggestions" and string IDs
        val wrappedInObject = """
            Here are your recommendations:
            ```json
            {
              "suggestions": [
                {
                  "slot_id": "1",
                  "recipe_id": "10",
                  "portions": "4",
                  "reason": "great dinner"
                }
              ]
            }
            ```
        """.trimIndent()
        val parsed1 = PlanSuggester.parseSuggestions(wrappedInObject, json)
        assertEquals(1, parsed1.size)
        assertEquals(1L, parsed1[0].slotId)
        assertEquals(10L, parsed1[0].recipeId)
        assertEquals(4, parsed1[0].portions)
        assertEquals("great dinner", parsed1[0].reason)

        // Object with "plan" and camelCase
        val wrappedInPlan = """
            {
              "plan": [
                {
                  "slotId": 2,
                  "recipeId": 20,
                  "portions": 2,
                  "reason": "quick lunch"
                }
              ]
            }
        """.trimIndent()
        val parsed2 = PlanSuggester.parseSuggestions(wrappedInPlan, json)
        assertEquals(1, parsed2.size)
        assertEquals(2L, parsed2[0].slotId)
        assertEquals(20L, parsed2[0].recipeId)
        assertEquals(2, parsed2[0].portions)

        // Top level array with decimal portions
        val topLevelArray = """
            [
              {
                "slot_id": 3,
                "recipe_id": 30,
                "portions": 4.0,
                "explanation": "uses leftover veggies"
              }
            ]
        """.trimIndent()
        val parsed3 = PlanSuggester.parseSuggestions(topLevelArray, json)
        assertEquals(1, parsed3.size)
        assertEquals(3L, parsed3[0].slotId)
        assertEquals(30L, parsed3[0].recipeId)
        assertEquals(4, parsed3[0].portions)
        assertEquals("uses leftover veggies", parsed3[0].reason)
    }
}
