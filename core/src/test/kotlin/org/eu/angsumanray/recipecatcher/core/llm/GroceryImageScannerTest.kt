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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryImageScannerTest {

    private val sampleAnthropicGroceryResponse = """
    {
      "id": "msg_grocery123",
      "type": "message",
      "role": "assistant",
      "content": [
        {
          "type": "text",
          "text": "{\n  \"items\": [\n    {\n      \"name\": \"Whole Milk\",\n      \"category\": \"dairy\",\n      \"estimatedQuantity\": \"1 Gallon\"\n    },\n    {\n      \"name\": \"Roma Tomatoes\",\n      \"category\": \"produce\",\n      \"estimatedQuantity\": \"1 kg\"\n    },\n    {\n      \"name\": \"Sourdough Bread\",\n      \"category\": \"bakery\",\n      \"estimatedQuantity\": \"1 loaf\"\n    }\n  ]\n}"
        }
      ],
      "model": "claude-haiku-5-5",
      "usage": {
        "input_tokens": 1500,
        "output_tokens": 200,
        "cache_read_input_tokens": 0
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
    fun scanGroceryImages_parsesValidReceiptResponse() = runBlocking {
        val client = createClient { _, _ ->
            HttpStatusCode.OK to sampleAnthropicGroceryResponse
        }

        val scanner = ClaudeGroceryImageScanner(
            httpClient = client,
            apiKeyProvider = { "sk-ant-test-key" }
        )

        val result = scanner.scanGroceryImages(listOf("fakeBase64ImageData"))
        assertTrue(result.isSuccess)
        val items = result.getOrThrow()
        assertEquals(3, items.size)
        assertEquals("Whole Milk", items[0].name)
        assertEquals("dairy", items[0].category)
        assertEquals("1 Gallon", items[0].estimatedQuantity)

        assertEquals("Roma Tomatoes", items[1].name)
        assertEquals("produce", items[1].category)

        assertEquals("Sourdough Bread", items[2].name)
        assertEquals("bakery", items[2].category)
    }

    @Test
    fun scanGroceryImages_emptyImages_returnsEmptyList() = runBlocking {
        val client = createClient { _, _ ->
            HttpStatusCode.OK to sampleAnthropicGroceryResponse
        }

        val scanner = ClaudeGroceryImageScanner(
            httpClient = client,
            apiKeyProvider = { "sk-ant-test-key" }
        )

        val result = scanner.scanGroceryImages(emptyList())
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun scanGroceryImages_handlesMarkdownFencedJson() = runBlocking {
        val fencedResponse = """
        {
          "content": [
            {
              "type": "text",
              "text": "```json\n{\n  \"items\": [\n    {\n      \"name\": \"Cheddar Cheese\",\n      \"category\": \"dairy\"\n    }\n  ]\n}\n```"
            }
          ]
        }
        """.trimIndent()

        val client = createClient { _, _ ->
            HttpStatusCode.OK to fencedResponse
        }

        val scanner = ClaudeGroceryImageScanner(
            httpClient = client,
            apiKeyProvider = { "sk-ant-test-key" }
        )

        val result = scanner.scanGroceryImages(listOf("fakeBase64"))
        assertTrue(result.isSuccess)
        val items = result.getOrThrow()
        assertEquals(1, items.size)
        assertEquals("Cheddar Cheese", items[0].name)
        assertEquals("dairy", items[0].category)
    }

    @Test
    fun scanGroceryImages_handlesApiError() = runBlocking {
        val client = createClient { _, _ ->
            HttpStatusCode.Unauthorized to """{"error":{"message":"Invalid API key"}}"""
        }

        val scanner = ClaudeGroceryImageScanner(
            httpClient = client,
            apiKeyProvider = { "bad-key" }
        )

        val result = scanner.scanGroceryImages(listOf("fakeBase64"))
        assertTrue(result.isFailure)
    }
}
