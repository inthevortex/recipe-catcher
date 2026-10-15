package org.eu.angsumanray.recipecatcher.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
data class ScannedGroceryItem(
    val name: String,
    val category: String = "pantry",
    val estimatedQuantity: String? = null
)

@Serializable
internal data class ScannedGroceryResponse(
    val items: List<ScannedGroceryItem> = emptyList()
)

interface GroceryImageScanner {
    suspend fun scanGroceryImages(base64Images: List<String>): Result<List<ScannedGroceryItem>>
}

class ClaudeGroceryImageScanner(
    private val httpClient: HttpClient,
    private val apiKeyProvider: suspend () -> String,
    private val modelIdProvider: suspend () -> String = { DEFAULT_MODEL_ID },
    private val json: Json = Json { ignoreUnknownKeys = true }
) : GroceryImageScanner {

    companion object {
        const val DEFAULT_MODEL_ID = "claude-haiku-5-5"
        const val ANTHROPIC_MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_TOKENS = 2048

        const val SYSTEM_PROMPT = """You are an expert grocery and consumable pantry item extractor.
Analyze the provided images, which may be:
1. Paper grocery store receipts or supermarket register slips
2. Screenshots of grocery delivery apps (e.g., Instacart, Blinkit, Zepto, Walmart, Amazon Fresh, BigBasket)
3. Photos of grocery bags with items visible
4. Photos of individual or grouped grocery/pantry products.

Rules:
1. Extract all recognized consumable grocery, pantry, and food items.
2. Ignore non-food items (e.g., plastic bags, detergents, floor cleaners, store discounts, taxes, receipt totals) unless they are kitchen consumables (foil, parchment paper).
3. Clean up item names into concise, common grocery names:
   - Remove brand prefixes or store codes (e.g. "KS ORG BANANAS 3LB" -> "Bananas", "AMZ FRESH WHOLE MILK 1L" -> "Whole Milk").
   - Convert all-caps text to Title Case (e.g. "RED BELL PEPPER" -> "Red Bell Pepper").
4. Assign each item a category from:
   - produce
   - dairy
   - meat & fish
   - grains & flours
   - spices
   - bakery
   - frozen
   - pantry
   - other
5. If quantity is clearly visible (e.g. "1 kg", "2 packs", "1L"), include it in estimatedQuantity; otherwise leave it null.
6. Return a valid JSON object strictly matching this schema:
{
  "items": [
    {
      "name": "Tomatoes",
      "category": "produce",
      "estimatedQuantity": "1 kg"
    }
  ]
}
Output ONLY the raw JSON object, without markdown code fences or conversational text."""
    }

    override suspend fun scanGroceryImages(base64Images: List<String>): Result<List<ScannedGroceryItem>> {
        if (base64Images.isEmpty()) {
            return Result.success(emptyList())
        }

        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return Result.failure(ApiKeyInvalidException("Anthropic API key is empty"))
        }

        val modelId = modelIdProvider().trim().ifEmpty { DEFAULT_MODEL_ID }

        val requestPayload = buildJsonObject {
            put("model", modelId)
            put("max_tokens", MAX_TOKENS)
            put("system", SYSTEM_PROMPT)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            base64Images.forEach { base64Data ->
                                add(
                                    buildJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", "image/jpeg")
                                            put("data", base64Data)
                                        }
                                    }
                                )
                            }
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put(
                                        "text",
                                        "Extract all consumable grocery and pantry items from these images into the specified JSON format."
                                    )
                                }
                            )
                        }
                    }
                )
            }
        }.toString()

        val (statusCode, responseBody) = try {
            val response = httpClient.post(ANTHROPIC_MESSAGES_URL) {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
                setBody(requestPayload)
            }
            response.status.value to response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }

        when (statusCode) {
            200 -> Unit
            401, 403 -> return Result.failure(ApiKeyInvalidException("Check API key: HTTP $statusCode"))
            404 -> return Result.failure(ModelNotFoundException("Model not found: HTTP 404 for model $modelId"))
            429, 529 -> return Result.failure(TransientApiException("Transient API error: HTTP $statusCode", statusCode))
            in 500..599 -> return Result.failure(TransientApiException("Server error: HTTP $statusCode", statusCode))
            else -> return Result.failure(RuntimeException("Anthropic API error: HTTP $statusCode $responseBody"))
        }

        return try {
            val textContent = parseContentText(responseBody)
            val cleanedJson = sanitizeJsonText(textContent)
            val parsedResponse = json.decodeFromString(ScannedGroceryResponse.serializer(), cleanedJson)
            Result.success(parsedResponse.items)
        } catch (e: Exception) {
            Result.failure(RuntimeException("Failed to parse grocery items from Claude vision response: ${e.message}", e))
        }
    }

    private fun parseContentText(responseBody: String): String {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val contentArray = root["content"]?.jsonArray
        return contentArray?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            ?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
    }

    private fun sanitizeJsonText(raw: String): String {
        val trimmed = raw.trim()
        val withoutFences = if (trimmed.startsWith("```")) {
            val startIndex = trimmed.indexOf('\n').takeIf { it != -1 }?.plus(1) ?: 3
            val endIndex = trimmed.lastIndexOf("```").takeIf { it != -1 && it > startIndex } ?: trimmed.length
            trimmed.substring(startIndex, endIndex).trim()
        } else {
            trimmed
        }
        val firstBrace = withoutFences.indexOf('{')
        val lastBrace = withoutFences.lastIndexOf('}')
        return if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            withoutFences.substring(firstBrace, lastBrace + 1)
        } else {
            withoutFences
        }
    }
}
