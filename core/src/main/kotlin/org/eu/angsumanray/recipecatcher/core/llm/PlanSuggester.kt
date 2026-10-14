package org.eu.angsumanray.recipecatcher.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@Serializable
data class EmptyPlanSlot(
    @SerialName("slot_id") val slotId: Long,
    val day: String,
    val slotName: String
)

@Serializable
data class ExistingPlanAssignment(
    @SerialName("slot_id") val slotId: Long,
    @SerialName("recipe_id") val recipeId: Long?,
    val recipeTitle: String,
    val day: String,
    val slotName: String
)

data class PlanSuggesterInput(
    val emptySlots: List<EmptyPlanSlot>,
    val existingAssignments: List<ExistingPlanAssignment> = emptyList(),
    val libraryRecipes: List<RecipeIndexEntry>,
    val inStockIngredients: List<String> = emptyList(),
    val lowStockIngredients: List<String> = emptyList(),
    val availableLeftovers: List<String> = emptyList()
)

@Serializable
data class ProposedSlotAssignment(
    @SerialName("slot_id") val slotId: Long,
    @SerialName("recipe_id") val recipeId: Long,
    val portions: Int = 4,
    val reason: String = ""
)

data class PlanSuggesterResult(
    val suggestions: List<ProposedSlotAssignment>,
    val usage: LlmUsage = LlmUsage(0, 0, 0)
)

class PlanSuggester(
    private val httpClient: HttpClient,
    private val apiKeyProvider: suspend () -> String,
    private val modelIdProvider: suspend () -> String = { ClaudeExtractor.DEFAULT_MODEL_ID },
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {

    companion object {
        const val MAX_SUGGESTIONS_PER_SLOT = 3

        const val SYSTEM_PROMPT = """You are an intelligent meal planning assistant for an offline-first recipe app.
Analyze the week's meal plan, available leftovers, pantry ingredients, and library recipes to suggest recipes for the empty plan slots.
Guidelines:
1. Suggest meals ONLY by recipe_id from the provided recipe index.
2. Prioritize using ingredients that are in stock or low in stock to prevent waste.
3. Ensure variety across the week and avoid repeating recipes already planned.
4. Favor user favourites and recipes that haven't been cooked recently.
5. Provide a concise reason for each suggestion (e.g. "uses your spinach before it goes", "quick 20-min dinner").
6. Provide up to 3 recipe suggestions per empty slot.

You MUST respond strictly with a valid JSON array in a ```json ... ``` code fence adhering to this schema:
```json
[
  {
    "slot_id": 12,
    "recipe_id": 42,
    "portions": 4,
    "reason": "uses your spinach before it goes"
  }
]
```"""
    }

    suspend fun suggestMeals(input: PlanSuggesterInput): Result<PlanSuggesterResult> {
        if (input.emptySlots.isEmpty() || input.libraryRecipes.isEmpty()) {
            return Result.success(PlanSuggesterResult(emptyList()))
        }

        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return Result.failure(ApiKeyInvalidException("API key is empty"))
        }
        val modelId = modelIdProvider().trim().ifEmpty { ClaudeExtractor.DEFAULT_MODEL_ID }

        val validRecipeIds = input.libraryRecipes.map { it.id }.toSet()
        val validSlotIds = input.emptySlots.map { it.slotId }.toSet()

        val promptText = buildString {
            appendLine("=== RECIPE INDEX (${input.libraryRecipes.size} recipes) ===")
            appendLine("id | title | tags | main ingredients | total min | complexity | spice | diet | contains | kcal | make-ahead / freezer | equipment | favourite | last cooked")
            input.libraryRecipes.forEach { appendLine(it.toIndexLine()) }
            appendLine()

            if (input.existingAssignments.isNotEmpty()) {
                appendLine("=== ALREADY PLANNED THIS WEEK ===")
                input.existingAssignments.forEach {
                    appendLine("- Day: ${it.day}, Slot: ${it.slotName} -> Recipe: ${it.recipeTitle}")
                }
                appendLine()
            }

            if (input.inStockIngredients.isNotEmpty()) {
                appendLine("=== IN-STOCK INGREDIENTS ===")
                appendLine(input.inStockIngredients.joinToString(", "))
                appendLine()
            }

            if (input.lowStockIngredients.isNotEmpty()) {
                appendLine("=== LOW-STOCK INGREDIENTS ===")
                appendLine(input.lowStockIngredients.joinToString(", "))
                appendLine()
            }

            if (input.availableLeftovers.isNotEmpty()) {
                appendLine("=== AVAILABLE LEFTOVERS ===")
                appendLine(input.availableLeftovers.joinToString(", "))
                appendLine()
            }

            appendLine("=== EMPTY SLOTS TO FILL ===")
            input.emptySlots.forEach {
                appendLine("- Slot ID: ${it.slotId}, Day: ${it.day}, Slot Name: ${it.slotName}")
            }
            appendLine()
            appendLine("Suggest up to 3 candidate recipes from the recipe index for each empty slot. Respond with JSON array.")
        }

        val requestPayload = buildJsonObject {
            put("model", modelId)
            put("max_tokens", 1500)
            putJsonArray("system") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", SYSTEM_PROMPT)
                })
            }
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", promptText)
                })
            }
        }.toString()

        return try {
            val response = httpClient.post(ClaudeExtractor.ANTHROPIC_MESSAGES_URL) {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", ClaudeExtractor.ANTHROPIC_VERSION)
                setBody(requestPayload)
            }

            val statusCode = response.status.value
            val responseBody = response.bodyAsText()

            if (statusCode !in 200..299) {
                return Result.failure(RuntimeException("Anthropic API error: HTTP $statusCode $responseBody"))
            }

            val anthropicResponse = json.decodeFromString<AnthropicMessageResponse>(responseBody)
            val textContent = anthropicResponse.content.firstOrNull { it.type == "text" }?.text
                ?: return Result.failure(RuntimeException("No text content returned from Claude"))

            val jsonBlock = extractJsonBlock(textContent)
            val rawSuggestions = json.decodeFromString<List<ProposedSlotAssignment>>(jsonBlock)

            // Validate:
            // 1. Must be in validSlotIds
            // 2. Must be in validRecipeIds
            // 3. At most 3 per slot
            val perSlotCount = mutableMapOf<Long, Int>()
            val validated = mutableListOf<ProposedSlotAssignment>()

            for (suggestion in rawSuggestions) {
                if (suggestion.slotId !in validSlotIds || suggestion.recipeId !in validRecipeIds) {
                    continue
                }
                val count = perSlotCount.getOrDefault(suggestion.slotId, 0)
                if (count < MAX_SUGGESTIONS_PER_SLOT) {
                    validated.add(suggestion)
                    perSlotCount[suggestion.slotId] = count + 1
                }
            }

            val usage = LlmUsage(
                inputTokens = anthropicResponse.usage?.inputTokens ?: 0,
                outputTokens = anthropicResponse.usage?.outputTokens ?: 0,
                cacheReadTokens = anthropicResponse.usage?.cacheReadInputTokens ?: 0,
                model = anthropicResponse.model ?: modelId
            )

            Result.success(PlanSuggesterResult(validated, usage))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun extractJsonBlock(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("```json")) {
            val content = trimmed.substringAfter("```json").substringBefore("```").trim()
            if (content.isNotEmpty()) return content
        }
        if (trimmed.startsWith("```")) {
            val content = trimmed.substringAfter("```").substringBefore("```").trim()
            if (content.isNotEmpty()) return content
        }

        val firstArr = trimmed.indexOf('[')
        val lastArr = trimmed.lastIndexOf(']')
        if (firstArr != -1 && lastArr > firstArr) {
            return trimmed.substring(firstArr, lastArr + 1)
        }

        return trimmed
    }
}
