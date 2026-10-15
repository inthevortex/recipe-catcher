package org.eu.angsumanray.recipecatcher.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.TextContent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun createClient(handler: (request: HttpRequestData, body: String) -> Pair<HttpStatusCode, String>): HttpClient {
        return HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json()
            }
            engine {
                addHandler { request ->
                    val body = (request.body as? TextContent)?.text.orEmpty()
                    val (status, content) = handler(request, body)
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

    private fun anthropicResponse(recipeJson: String, stopReason: String = "end_turn"): String {
        val escaped = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(recipeJson))
        return """{"id":"msg_x","type":"message","role":"assistant","model":"claude-haiku-5-5","stop_reason":"$stopReason",
            "content":[{"type":"thinking","thinking":"","signature":"sig"},{"type":"text","text":$escaped}],
            "usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":0}}"""
    }

    private fun recipeJson(
        servings: Int = 4,
        prep: Int = 10,
        complexity: String = "medium",
        dietType: String = "vegetarian",
        spice: String = "medium",
        category: String = "dairy",
        suggestionKind: String = "tag"
    ) = """{"is_recipe":true,"title":"T","description":"","servings":$servings,"prep_minutes":$prep,"cook_minutes":0,
        "total_minutes":5000,"cuisine":"unknown","complexity":"$complexity","complexity_reason":"","spice_level":"$spice",
        "diet_type":"$dietType","contains":["Dairy"],"kcal_per_serving":0,"make_ahead":false,"freezer_friendly":false,
        "ingredient_groups":[{"name":"","items":[{"quantity":0,"quantity_max":0,"unit":"","name":"salt","base_name":"salt",
        "category":"$category","note":"to taste","raw":"salt to taste"}]}],"steps":[{"text":"Mix.","timer_seconds":0}],
        "equipment":["KADAI"],"consumables":["foil"],"tags":[],"new_list_suggestions":[{"kind":"$suggestionKind","name":"tandoor"}],
        "confidence":0.5}"""

    @Test
    fun `request uses Messages API headers, output_config effort and strict schema without sampling params`() = runBlocking {
        var captured: HttpRequestData? = null
        var capturedBody = ""
        val client = createClient { request, body ->
            captured = request
            capturedBody = body
            HttpStatusCode.OK to sampleAnthropicRecipeJson
        }
        ClaudeExtractor(client, { "test-api-key" }, { "claude-haiku-5-5" })
            .extract(ExtractionInput(SourceType.INSTAGRAM, "recipe text", allowedTags = listOf("indian")))

        val request = captured!!
        assertEquals("https://api.anthropic.com/v1/messages", request.url.toString())
        assertEquals("POST", request.method.value)
        assertEquals("test-api-key", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertTrue((request.body as TextContent).contentType.toString().startsWith("application/json"))

        val root = Json.parseToJsonElement(capturedBody).jsonObject
        assertEquals("claude-haiku-5-5", root["model"]!!.jsonPrimitive.content)
        assertEquals(2000, root["max_tokens"]!!.jsonPrimitive.content.toInt())
        listOf("temperature", "top_p", "top_k", "effort", "thinking").forEach { assertFalse(it, root.containsKey(it)) }

        val outputConfig = root["output_config"]!!.jsonObject
        assertEquals("low", outputConfig["effort"]!!.jsonPrimitive.content)
        val format = outputConfig["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)

        val system = root["system"]!!.jsonArray.single().jsonObject
        assertEquals("ephemeral", system["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val userText = root["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(userText.contains("recipe text"))
        assertTrue(userText.contains("Allowed tags: indian"))

        // Every object in the schema: all properties required, no extras, no nullable unions.
        fun checkObject(node: JsonObject, path: String) {
            if (node["type"]?.jsonPrimitive?.content == "object") {
                val props = node["properties"]!!.jsonObject.keys
                val required = node["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                assertEquals(path, props, required)
                assertEquals(path, "false", node["additionalProperties"]!!.jsonPrimitive.content)
                node["properties"]!!.jsonObject.forEach { (k, v) -> checkObject(v.jsonObject, "$path.$k") }
            }
            assertFalse(path, node["type"] is kotlinx.serialization.json.JsonArray)
            node["items"]?.let { checkObject(it.jsonObject, "$path[]") }
        }
        val schema = format["schema"]!!.jsonObject
        checkObject(schema, "$")
        val dietEnum = schema["properties"]!!.jsonObject["diet_type"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(dietEnum.containsAll(listOf("vegan", "vegetarian", "eggetarian", "non-veg", "unknown")))
    }

    @Test
    fun `validation clamps numbers, normalizes enums and drops values outside the lists`() = runBlocking {
        val client = createClient { _, _ ->
            HttpStatusCode.OK to anthropicResponse(
                recipeJson(servings = 120, prep = -5, complexity = "Hard", dietType = "Non-Veg", spice = "fiery",
                    category = "Spices", suggestionKind = "gadget")
            )
        }
        val recipe = ClaudeExtractor(client, { "k" }).extract(
            ExtractionInput(
                SourceType.INSTAGRAM, "text",
                allowedDiet = listOf("dairy"),
                allowedEquipment = listOf("kadai"),
                allowedConsumables = listOf("aluminium foil")
            )
        ).getOrThrow()

        assertEquals(50, recipe.servings)
        assertNull(recipe.prepMinutes)
        assertNull(recipe.cookMinutes)
        assertEquals(1440, recipe.totalMinutes)
        assertNull(recipe.cuisine)
        assertEquals("hard", recipe.complexity)
        assertEquals("non-veg", recipe.dietType)
        assertNull(recipe.spiceLevel)
        assertEquals(listOf("dairy"), recipe.contains)
        assertEquals(listOf("kadai"), recipe.equipment)
        assertTrue(recipe.consumables.isEmpty())
        assertTrue(recipe.newListSuggestions.isEmpty())
        val salt = recipe.ingredientGroups.single().items.single()
        assertEquals("spices", salt.category)
        assertNull(salt.quantity)
        assertEquals("to taste", salt.note)
        assertNull(recipe.ingredientGroups.single().name)
        assertNull(recipe.steps.single().timerSeconds)
        assertEquals("claude-haiku-5-5", recipe.usage.model)
    }

    @Test
    fun `servings below one map to null`() = runBlocking {
        val client = createClient { _, _ -> HttpStatusCode.OK to anthropicResponse(recipeJson(servings = 0)) }
        val recipe = ClaudeExtractor(client, { "k" }).extract(ExtractionInput(SourceType.INSTAGRAM, "t")).getOrThrow()
        assertNull(recipe.servings)
    }

    @Test
    fun `status codes map to typed exceptions`() = runBlocking {
        val expectations = mapOf(
            403 to ApiKeyInvalidException::class,
            401 to ApiKeyInvalidException::class,
            404 to ModelNotFoundException::class,
            429 to TransientApiException::class,
            529 to TransientApiException::class,
            500 to TransientApiException::class,
            503 to TransientApiException::class
        )
        expectations.forEach { (code, type) ->
            val client = createClient { _, _ -> HttpStatusCode.fromValue(code) to """{"type":"error"}""" }
            val error = ClaudeExtractor(client, { "k" }).extract(ExtractionInput(SourceType.INSTAGRAM, "t")).exceptionOrNull()
            assertTrue("HTTP $code gave $error", type.isInstance(error))
        }
        val badRequest = createClient { _, _ -> HttpStatusCode.BadRequest to """{"type":"error"}""" }
        val error = ClaudeExtractor(badRequest, { "k" }).extract(ExtractionInput(SourceType.INSTAGRAM, "t")).exceptionOrNull()
        assertFalse(error is TransientApiException)
    }

    @Test
    fun `empty api key fails without a request`() = runBlocking {
        var called = false
        val client = createClient { _, _ -> called = true; HttpStatusCode.OK to sampleAnthropicRecipeJson }
        val result = ClaudeExtractor(client, { "  " }).extract(ExtractionInput(SourceType.INSTAGRAM, "t"))
        assertTrue(result.exceptionOrNull() is ApiKeyInvalidException)
        assertFalse(called)
    }

    @Test
    fun `truncated or refused responses fail instead of decoding partial json`() = runBlocking {
        listOf("max_tokens", "refusal").forEach { reason ->
            val client = createClient { _, _ -> HttpStatusCode.OK to anthropicResponse(recipeJson(), stopReason = reason) }
            val result = ClaudeExtractor(client, { "k" }).extract(ExtractionInput(SourceType.INSTAGRAM, "t"))
            assertTrue(result.isFailure)
            assertFalse(result.exceptionOrNull() is NoRecipeFoundException)
        }
    }

    @Test
    fun `no recipe failure carries usage for logging`() = runBlocking {
        val client = createClient { _, _ ->
            HttpStatusCode.OK to anthropicResponse(recipeJson().replace("\"is_recipe\":true", "\"is_recipe\":false"))
        }
        val error = ClaudeExtractor(client, { "k" }).extract(ExtractionInput(SourceType.INSTAGRAM, "t")).exceptionOrNull()
        assertTrue(error is NoRecipeFoundException)
        assertEquals(10, (error as NoRecipeFoundException).usage?.inputTokens)
    }

    @Test
    fun `buildRequestPayload with image blocks produces valid Anthropic vision content structure`() {
        val extractor = ClaudeExtractor(createClient { _, _ -> HttpStatusCode.OK to "" }, { "k" })
        val dummyBase64_1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        val dummyBase64_2 = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEklEQVR42mNk+M/AwAAEDAwMABx4AwsAAA=="

        val payloadString = extractor.buildRequestPayload(
            modelId = "claude-haiku-5-5",
            userContent = "Source text:\n(Recipe in attached photos)",
            images = listOf(dummyBase64_1, dummyBase64_2)
        )

        val root = Json.parseToJsonElement(payloadString).jsonObject
        val userMessage = root["messages"]!!.jsonArray[0].jsonObject
        val contentArray = userMessage["content"]!!.jsonArray

        assertEquals(3, contentArray.size)

        // First block: image 1
        val block1 = contentArray[0].jsonObject
        assertEquals("image", block1["type"]!!.jsonPrimitive.content)
        val source1 = block1["source"]!!.jsonObject
        assertEquals("base64", source1["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", source1["media_type"]!!.jsonPrimitive.content)
        assertEquals(dummyBase64_1, source1["data"]!!.jsonPrimitive.content)

        // Second block: image 2
        val block2 = contentArray[1].jsonObject
        assertEquals("image", block2["type"]!!.jsonPrimitive.content)
        val source2 = block2["source"]!!.jsonObject
        assertEquals("base64", source2["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", source2["media_type"]!!.jsonPrimitive.content)
        assertEquals(dummyBase64_2, source2["data"]!!.jsonPrimitive.content)

        // Third block: text prompt
        val block3 = contentArray[2].jsonObject
        assertEquals("text", block3["type"]!!.jsonPrimitive.content)
        assertTrue(block3["text"]!!.jsonPrimitive.content.contains("Source text:"))
    }

    @Test
    fun `extract with sourceImages sends vision content blocks and parses successfully`() = runBlocking {
        var capturedBody = ""
        val client = createClient { _, body ->
            capturedBody = body
            HttpStatusCode.OK to sampleAnthropicRecipeJson
        }

        val extractor = ClaudeExtractor(client, { "test-api-key" }, { "claude-haiku-5-5" })
        val dummyBase64 = "AQIDBA=="
        val input = ExtractionInput(
            sourceType = SourceType.IMAGE,
            sourceText = "",
            sourceImages = listOf(dummyBase64)
        )

        val result = extractor.extract(input)
        assertTrue(result.isSuccess)
        val recipe = result.getOrThrow()
        assertEquals("Paneer Butter Masala", recipe.title)

        // Verify request payload contained the image block
        assertTrue(capturedBody.contains("\"type\":\"image\""))
        assertTrue(capturedBody.contains(dummyBase64))
        assertTrue(capturedBody.contains("Recipe content is present in the attached image(s)"))
    }

    @Test
    fun `image request rejected with 400 or 413 maps to ImageRejectedException`() = runBlocking {
        val imageInput = ExtractionInput(sourceType = SourceType.IMAGE, sourceText = "", sourceImages = listOf("AQIDBA=="))
        val cases = listOf(
            400 to """{"type":"error","error":{"type":"invalid_request_error","message":"image exceeds 5 MB maximum"}}""",
            413 to """{"type":"error","error":{"type":"request_too_large"}}"""
        )
        for ((code, body) in cases) {
            val client = createClient { _, _ -> HttpStatusCode.fromValue(code) to body }
            val result = ClaudeExtractor(client, { "k" }).extract(imageInput)
            assertTrue("HTTP $code", result.exceptionOrNull() is ImageRejectedException)
        }

        val textOnly = createClient { _, _ -> HttpStatusCode.BadRequest to """{"error":{"message":"bad image field"}}""" }
        val textResult = ClaudeExtractor(textOnly, { "k" }).extract(imageInput.copy(sourceImages = emptyList(), sourceText = "x"))
        assertFalse(textResult.exceptionOrNull() is ImageRejectedException)
    }
}
