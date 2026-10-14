package org.eu.angsumanray.recipecatcher.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeExtractorTest {

    private val sampleAnthropicRecipeJson = """
    {
      "id": "msg_01ABC",
      "type": "message",
      "role": "assistant",
      "content": [
        {
          "type": "text",
          "text": "{\n  \"is_recipe\": true,\n  \"title\": \"Paneer Butter Masala\",\n  \"description\": \"Creamy gravy with paneer\",\n  \"servings\": 4,\n  \"prep_minutes\": 10,\n  \"cook_minutes\": 25,\n  \"total_minutes\": 35,\n  \"cuisine\": \"Indian\",\n  \"complexity\": \"medium\",\n  \"complexity_reason\": \"Two parts: gravy and paneer\",\n  \"spice_level\": \"medium\",\n  \"diet_type\": \"vegetarian\",\n  \"contains\": [\"dairy\", \"nuts\", \"unknown_allergen\"],\n  \"kcal_per_serving\": 450,\n  \"make_ahead\": true,\n  \"freezer_friendly\": false,\n  \"ingredient_groups\": [\n    {\n      \"name\": \"Gravy\",\n      \"items\": [\n        {\n          \"quantity\": 2.0,\n          \"quantity_max\": 0.0,\n          \"unit\": \"tbsp\",\n          \"name\": \"butter\",\n          \"base_name\": \"butter\",\n          \"category\": \"dairy\",\n          \"note\": \"\",\n          \"raw\": \"2 tbsp butter\"\n        }\n      ]\n    }\n  ],\n  \"steps\": [\n    {\n      \"text\": \"Simmer gravy.\",\n      \"timer_seconds\": 600\n    }\n  ],\n  \"equipment\": [\"kadai\", \"unsupported_gadget\"],\n  \"consumables\": [],\n  \"tags\": [\"indian\", \"paneer\", \"random_tag\"],\n  \"new_list_suggestions\": [\n    {\n      \"kind\": \"tag\",\n      \"name\": \"restaurant-style\"\n    }\n  ],\n  \"confidence\": 0.95\n}"
        }
      ],
      "model": "claude-haiku-5-5",
      "usage": {
        "input_tokens": 1200,
        "output_tokens": 450,
        "cache_read_input_tokens": 512
      }
    }
    """.trimIndent()

    private fun createClient(handler: (requestUrl: String, body: String) -> Pair<HttpStatusCode, String>): HttpClient {
        return HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json()
            }
            engine {
                addHandler { request ->
                    val (status, content) = handler(request.url.toString(), "")
                    respond(
                        content = content,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
    }

    @Test
    fun `extract success returns valid ExtractedRecipe and tracks usage`() = runBlocking {
        val client = createClient { _, _ ->
            HttpStatusCode.OK to sampleAnthropicRecipeJson
        }
        val extractor = ClaudeExtractor(client, { "test-api-key" }, { "claude-haiku-5-5" })
        val input = ExtractionInput(
            sourceType = SourceType.INSTAGRAM,
            sourceText = "Paneer butter masala recipe...",
            allowedTags = listOf("indian", "paneer"),
            allowedDiet = listOf("dairy", "nuts"),
            allowedEquipment = listOf("kadai"),
            allowedConsumables = listOf("parchment paper")
        )

        val result = extractor.extract(input)
        assertTrue(result.isSuccess)
        val recipe = result.getOrThrow()

        assertTrue(recipe.isRecipe)
        assertEquals("Paneer Butter Masala", recipe.title)
        assertEquals(4, recipe.servings)
        assertEquals(10, recipe.prepMinutes)
        assertEquals(25, recipe.cookMinutes)
        assertEquals(35, recipe.totalMinutes)
        assertEquals(450, recipe.kcalPerServing)
        assertEquals(listOf("dairy", "nuts"), recipe.contains)
        assertEquals(listOf("kadai"), recipe.equipment)
        assertEquals(listOf("indian", "paneer"), recipe.tags)

        // Ingredients and groups
        assertEquals(1, recipe.ingredientGroups.size)
        val gravyGroup = recipe.ingredientGroups[0]
        assertEquals("Gravy", gravyGroup.name)
        val butter = gravyGroup.items[0]
        assertEquals(2.0, butter.quantity)
        assertNull(butter.quantityMax) // 0 mapped to null
        assertNull(butter.note) // "" mapped to null
        assertEquals("tbsp", butter.unit)

        // Usage
        assertEquals(1200, recipe.usage.inputTokens)
        assertEquals(450, recipe.usage.outputTokens)
        assertEquals(512, recipe.usage.cacheReadTokens)
    }

    @Test
    fun `extract handles 401 ApiKeyInvalidException`() = runBlocking {
        val client = createClient { _, _ ->
            HttpStatusCode.Unauthorized to """{"error":{"type":"authentication_error","message":"invalid api key"}}"""
        }
        val extractor = ClaudeExtractor(client, { "bad-key" })
        val input = ExtractionInput(SourceType.INSTAGRAM, "recipe")
        val result = extractor.extract(input)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ApiKeyInvalidException)
    }

    @Test
    fun `extract handles 404 ModelNotFoundException`() = runBlocking {
        val client = createClient { _, _ ->
            HttpStatusCode.NotFound to """{"error":{"type":"not_found_error","message":"model not found"}}"""
        }
        val extractor = ClaudeExtractor(client, { "valid-key" })
        val input = ExtractionInput(SourceType.INSTAGRAM, "recipe")
        val result = extractor.extract(input)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ModelNotFoundException)
    }

    @Test
    fun `extract handles 429 TransientApiException`() = runBlocking {
        val client = createClient { _, _ ->
            HttpStatusCode.TooManyRequests to """{"error":{"type":"rate_limit_error","message":"rate limit"}}"""
        }
        val extractor = ClaudeExtractor(client, { "valid-key" })
        val input = ExtractionInput(SourceType.INSTAGRAM, "recipe")
        val result = extractor.extract(input)

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is TransientApiException)
        assertEquals(429, (ex as TransientApiException).statusCode)
    }

    @Test
    fun `extract handles non-recipe NoRecipeFoundException`() = runBlocking {
        val nonRecipeJson = """
        {
          "id": "msg_02DEF",
          "type": "message",
          "role": "assistant",
          "content": [
            {
              "type": "text",
              "text": "{\n  \"is_recipe\": false,\n  \"title\": \"\",\n  \"description\": \"\",\n  \"servings\": 0,\n  \"prep_minutes\": 0,\n  \"cook_minutes\": 0,\n  \"total_minutes\": 0,\n  \"cuisine\": \"\",\n  \"complexity\": \"\",\n  \"complexity_reason\": \"\",\n  \"spice_level\": \"\",\n  \"diet_type\": \"\",\n  \"contains\": [],\n  \"kcal_per_serving\": 0,\n  \"make_ahead\": false,\n  \"freezer_friendly\": false,\n  \"ingredient_groups\": [],\n  \"steps\": [],\n  \"equipment\": [],\n  \"consumables\": [],\n  \"tags\": [],\n  \"new_list_suggestions\": [],\n  \"confidence\": 0.1\n}"
            }
          ],
          "model": "claude-haiku-5-5",
          "usage": {"input_tokens": 100, "output_tokens": 50}
        }
        """.trimIndent()

        val client = createClient { _, _ ->
            HttpStatusCode.OK to nonRecipeJson
        }
        val extractor = ClaudeExtractor(client, { "valid-key" })
        val input = ExtractionInput(SourceType.INSTAGRAM, "check link in bio")
        val result = extractor.extract(input)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NoRecipeFoundException)
    }
}
