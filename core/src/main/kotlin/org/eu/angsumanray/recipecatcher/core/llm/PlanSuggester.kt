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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    val availableLeftovers: List<String> = emptyList(),
    /** Recipes whose cooked leftovers are still available; cooking them again is a last resort. */
    val leftoverRecipeIds: Set<Long> = emptySet(),
    val today: String? = null
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
        const val MIN_PORTIONS = 1
        const val MAX_PORTIONS = 50

        const val SYSTEM_PROMPT = """You are an intelligent meal planning assistant for an offline-first recipe app.
Analyze the week's meal plan, available leftovers, pantry ingredients, and library recipes to suggest recipes for the empty plan slots.
Guidelines:
1. Suggest meals ONLY by recipe_id from the provided recipe index.
2. Prioritize using ingredients that are in stock or low in stock to prevent waste.
3. Ensure variety across the week and avoid repeating recipes already planned.
4. Don't suggest cooking a recipe again while its leftovers are still available.
5. Favor user favourites and recipes that haven't been cooked recently.
6. Provide a concise reason for each suggestion (e.g. "uses your spinach before it goes", "quick 20-min dinner").
7. Provide up to 3 recipe suggestions per empty slot, best first.

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

        /**
         * Validates raw proposals: known slots and recipes only, one entry per slot and recipe,
         * portions clamped to [MIN_PORTIONS]..[MAX_PORTIONS], at most [MAX_SUGGESTIONS_PER_SLOT] per slot.
         */
        fun validate(
            raw: List<ProposedSlotAssignment>,
            validSlotIds: Set<Long>,
            validRecipeIds: Set<Long>
        ): List<ProposedSlotAssignment> {
            val perSlotCount = mutableMapOf<Long, Int>()
            val seen = mutableSetOf<Pair<Long, Long>>()
            val validated = mutableListOf<ProposedSlotAssignment>()
            for (suggestion in raw) {
                if (suggestion.slotId !in validSlotIds || suggestion.recipeId !in validRecipeIds) continue
                if (!seen.add(suggestion.slotId to suggestion.recipeId)) continue
                val count = perSlotCount.getOrDefault(suggestion.slotId, 0)
                if (count >= MAX_SUGGESTIONS_PER_SLOT) continue
                validated.add(
                    suggestion.copy(
                        portions = suggestion.portions.coerceIn(MIN_PORTIONS, MAX_PORTIONS),
                        reason = suggestion.reason.trim()
                    )
                )
                perSlotCount[suggestion.slotId] = count + 1
            }
            return validated
        }

        /**
         * One pick per slot for "Fill empty slots", in [slotOrder]. Each slot takes its best candidate
         * that isn't already planned, picked for an earlier slot, or still around as leftovers; if every
         * candidate repeats, it falls back to the model's first choice.
         */
        fun defaultSelection(
            suggestions: List<ProposedSlotAssignment>,
            slotOrder: List<Long>,
            alreadyPlannedRecipeIds: Set<Long> = emptySet(),
            leftoverRecipeIds: Set<Long> = emptySet()
        ): List<ProposedSlotAssignment> {
            val bySlot = suggestions.groupBy { it.slotId }
            val used = (alreadyPlannedRecipeIds + leftoverRecipeIds).toMutableSet()
            val picks = mutableListOf<ProposedSlotAssignment>()
            for (slotId in slotOrder.distinct()) {
                val candidates = bySlot[slotId].orEmpty()
                val pick = candidates.firstOrNull { it.recipeId !in used } ?: candidates.firstOrNull() ?: continue
                used.add(pick.recipeId)
                picks.add(pick)
            }
            return picks
        }

        /**
         * Robustly parses Claude's plan suggestions, accepting:
         * - A top-level JSON array
         * - A JSON object containing an array under "suggestions", "plan", "meals", "recipes", or any key
         * - Numeric or string slot_id and recipe_id
         * - snake_case (slot_id) or camelCase (slotId) or shorthand (slot)
         * - Numeric, string, or decimal portions
         * - Reason under "reason", "explanation", or "note"
         */
        fun parseSuggestions(rawText: String, json: Json): List<ProposedSlotAssignment> {
            val jsonText = AnthropicSupport.extractJsonBlock(rawText)
            val root = try {
                json.parseToJsonElement(jsonText)
            } catch (e: Exception) {
                val firstArr = rawText.indexOf('[')
                val lastArr = rawText.lastIndexOf(']')
                if (firstArr != -1 && lastArr > firstArr) {
                    json.parseToJsonElement(rawText.substring(firstArr, lastArr + 1))
                } else {
                    val firstObj = rawText.indexOf('{')
                    val lastObj = rawText.lastIndexOf('}')
                    if (firstObj != -1 && lastObj > firstObj) {
                        json.parseToJsonElement(rawText.substring(firstObj, lastObj + 1))
                    } else {
                        throw e
                    }
                }
            }

            val array: JsonArray = when (root) {
                is JsonArray -> root
                is JsonObject -> {
                    (root["suggestions"] as? JsonArray)
                        ?: (root["plan"] as? JsonArray)
                        ?: (root["meals"] as? JsonArray)
                        ?: (root["recipes"] as? JsonArray)
                        ?: (root["slots"] as? JsonArray)
                        ?: root.values.firstOrNull { it is JsonArray } as? JsonArray
                        ?: throw SerializationException("Expected a JSON array or object containing suggestions array")
                }
                else -> throw SerializationException("Unexpected JSON root: $root")
            }

            return array.mapNotNull { element ->
                if (element !is JsonObject) return@mapNotNull null

                val slotIdPrimitive = (element["slot_id"] as? JsonPrimitive)
                    ?: (element["slotId"] as? JsonPrimitive)
                    ?: (element["slot"] as? JsonPrimitive)
                val slotId = slotIdPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null

                val recipeIdPrimitive = (element["recipe_id"] as? JsonPrimitive)
                    ?: (element["recipeId"] as? JsonPrimitive)
                    ?: (element["recipe"] as? JsonPrimitive)
                val recipeId = recipeIdPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null

                val portionsPrimitive = (element["portions"] as? JsonPrimitive)
                    ?: (element["portion"] as? JsonPrimitive)
                val portions = portionsPrimitive?.content?.toDoubleOrNull()?.toInt() ?: 4

                val reasonPrimitive = (element["reason"] as? JsonPrimitive)
                    ?: (element["explanation"] as? JsonPrimitive)
                    ?: (element["note"] as? JsonPrimitive)
                val reason = reasonPrimitive?.content ?: ""

                ProposedSlotAssignment(
                    slotId = slotId,
                    recipeId = recipeId,
                    portions = portions,
                    reason = reason
                )
            }
        }
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
            input.today?.let {
                appendLine("Today is $it.")
                appendLine()
            }
            appendLine("=== RECIPE INDEX (${input.libraryRecipes.size} recipes) ===")
            appendLine(RecipeIndexEntry.HEADER)
            input.libraryRecipes.forEach { appendLine(it.toIndexLine()) }
            appendLine()

            if (input.existingAssignments.isNotEmpty()) {
                appendLine("=== ALREADY PLANNED THIS WEEK ===")
                input.existingAssignments.forEach {
                    val id = it.recipeId?.let { rId -> " (recipe_id $rId)" } ?: ""
                    appendLine("- Day: ${it.day}, Slot: ${it.slotName} -> ${it.recipeTitle}$id")
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
            put("max_tokens", 4096)
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
                return Result.failure(AnthropicSupport.httpError(statusCode, responseBody, modelId))
            }

            val anthropicResponse = json.decodeFromString<AnthropicMessageResponse>(responseBody)
            val textContent = AnthropicSupport.responseText(anthropicResponse)
            if (textContent.isBlank()) {
                return Result.failure(RuntimeException("No text content returned from Claude"))
            }

            val rawSuggestions = parseSuggestions(textContent, json)

            val usage = LlmUsage(
                inputTokens = anthropicResponse.usage?.inputTokens ?: 0,
                outputTokens = anthropicResponse.usage?.outputTokens ?: 0,
                cacheReadTokens = anthropicResponse.usage?.cacheReadInputTokens ?: 0,
                model = anthropicResponse.model ?: modelId
            )

            Result.success(PlanSuggesterResult(validate(rawSuggestions, validSlotIds, validRecipeIds), usage))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
