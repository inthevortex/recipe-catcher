package org.eu.angsumanray.recipecatcher.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class ClaudeExtractor(
    private val httpClient: HttpClient,
    private val apiKeyProvider: suspend () -> String,
    private val modelIdProvider: suspend () -> String = { DEFAULT_MODEL_ID },
    private val json: Json = Json { ignoreUnknownKeys = true }
) : RecipeExtractor {

    companion object {
        const val DEFAULT_MODEL_ID = "claude-haiku-5-5"
        const val ANTHROPIC_MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_TOKENS = 2000
        const val EFFORT = "low"

        const val UNKNOWN = "unknown"
        val COMPLEXITY_VALUES = listOf("easy", "medium", "hard")
        val SPICE_LEVEL_VALUES = listOf("mild", "medium", "hot")
        val DIET_TYPE_VALUES = listOf("vegan", "vegetarian", "eggetarian", "non-veg")
        val CATEGORY_VALUES = listOf(
            "vegetables", "fruit", "dairy", "meat & fish", "grains & flours", "spices", "pantry", "other"
        )
        val SUGGESTION_KIND_VALUES = listOf("tag", "diet", "equipment", "consumable")

        const val SYSTEM_PROMPT = """You are an expert recipe extraction system. Extract structured recipe information from the provided source text.
Rules:
1. Extract only what is in the text; never invent ingredients, quantities or steps.
2. If the text contains no recipe (e.g. 'recipe in bio' with no ingredients/steps), set is_recipe: false and empty/zero values.
3. Quantities as decimals (½ -> 0.5); ranges use quantity_max; 'to taste' gives a 0 quantity with the phrase in note.
4. Use metric units: g, kg, ml, l, plus tsp, tbsp, pinch and pc. Convert imperial and US units: oz -> g, lb -> g or kg, fl oz -> ml, cups -> ml (1 US cup = 240 ml), a stick of butter -> 113 g, inches -> cm, and °F -> °C inside steps. Round converted values sensibly. Keep unknown units as written; the original line always stays in raw.
5. Write output in English, translating when the source is in another language. Keep Indian ingredient names common in English (paneer, ghee, jeera) as they are.
6. Split steps into single actions; set timer_seconds only when a duration is stated.
7. Pick tags only from the allowed list; put other ideas in new_list_suggestions.
8. List equipment and consumables from allowed lists; leave out basic kitchen tools (knife, spoon, chopping board, stove). Put non-list necessities into new_list_suggestions.
9. Rate complexity: easy (<= 8 ingredients, < 30 min hands-on, basic techniques), hard (several components, special techniques or long processes), otherwise medium; provide a one-line complexity_reason.
10. Rate spice_level as mild, medium or hot. Set make_ahead or freezer_friendly only when stated or clearly preserved; otherwise false.
11. Set diet_type from ingredients: vegan, vegetarian, eggetarian, non-veg. List hidden and obvious allergens/sensitivities in contains from allowed diet list.
12. Estimate kcal_per_serving, rounded to nearest 10; 0 when missing or too vague.
13. For each ingredient provide a singular base_name with no descriptors ('onion') and a category from: vegetables, fruit, dairy, meat & fish, grains & flours, spices, pantry, other.
All fields must be present and non-null. Use 0 for unknown numbers, '' for unknown strings, and 'unknown' for unknown enum values."""
    }

    override suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe> {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return Result.failure(ApiKeyInvalidException("API key is empty"))
        }

        val modelId = modelIdProvider().trim().ifEmpty { DEFAULT_MODEL_ID }
        val userContent = buildUserTurnText(input)
        val requestPayload = buildRequestPayload(modelId, userContent)

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
            val anthropicResponse = json.decodeFromString<AnthropicMessageResponse>(responseBody)
            if (anthropicResponse.stopReason == "refusal" || anthropicResponse.stopReason == "max_tokens") {
                return Result.failure(RuntimeException("Extraction stopped early: ${anthropicResponse.stopReason}"))
            }
            val textContent = anthropicResponse.content.firstOrNull { it.type == "text" }?.text
                ?: return Result.failure(RuntimeException("No text content in Anthropic response"))

            val rawRecipe = json.decodeFromString<RawExtractedRecipe>(textContent)

            val usage = LlmUsage(
                inputTokens = anthropicResponse.usage?.inputTokens ?: 0,
                outputTokens = anthropicResponse.usage?.outputTokens ?: 0,
                cacheReadTokens = anthropicResponse.usage?.cacheReadInputTokens ?: 0,
                model = anthropicResponse.model ?: modelId
            )

            validateAndMap(rawRecipe, input, usage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildUserTurnText(input: ExtractionInput): String = buildString {
        appendLine("Source type: ${input.sourceType.value}")
        appendLine("Author: ${input.author ?: "unknown"}")
        appendLine("Top comment: ${input.topComment ?: "none"}")
        appendLine("Allowed tags: ${input.allowedTags.joinToString(", ")}")
        appendLine("Allowed diet contains: ${input.allowedDiet.joinToString(", ")}")
        appendLine("Allowed equipment: ${input.allowedEquipment.joinToString(", ")}")
        appendLine("Allowed consumables: ${input.allowedConsumables.joinToString(", ")}")
        appendLine("Allow new tags: ${input.allowNewTags}")
        appendLine()
        appendLine("Source text:")
        appendLine(input.sourceText)
    }

    internal fun buildRequestPayload(modelId: String, userContent: String): String {
        val root = buildJsonObject {
            put("model", modelId)
            put("max_tokens", MAX_TOKENS)
            putJsonArray("system") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", SYSTEM_PROMPT)
                    putJsonObject("cache_control") {
                        put("type", "ephemeral")
                    }
                })
            }
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userContent)
                })
            }
            putJsonObject("output_config") {
                put("effort", EFFORT)
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", buildJsonSchema())
                }
            }
        }
        return root.toString()
    }

    private fun buildJsonSchema() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("is_recipe") { put("type", "boolean") }
            putJsonObject("title") { put("type", "string") }
            putJsonObject("description") { put("type", "string") }
            putJsonObject("servings") { put("type", "integer") }
            putJsonObject("prep_minutes") { put("type", "integer") }
            putJsonObject("cook_minutes") { put("type", "integer") }
            putJsonObject("total_minutes") { put("type", "integer") }
            putJsonObject("cuisine") { put("type", "string") }
            putJsonObject("complexity") { stringEnum(COMPLEXITY_VALUES + UNKNOWN) }
            putJsonObject("complexity_reason") { put("type", "string") }
            putJsonObject("spice_level") { stringEnum(SPICE_LEVEL_VALUES + UNKNOWN) }
            putJsonObject("diet_type") { stringEnum(DIET_TYPE_VALUES + UNKNOWN) }
            putJsonObject("contains") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("kcal_per_serving") { put("type", "integer") }
            putJsonObject("make_ahead") { put("type", "boolean") }
            putJsonObject("freezer_friendly") { put("type", "boolean") }
            putJsonObject("ingredient_groups") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("items") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("quantity") { put("type", "number") }
                                    putJsonObject("quantity_max") { put("type", "number") }
                                    putJsonObject("unit") { put("type", "string") }
                                    putJsonObject("name") { put("type", "string") }
                                    putJsonObject("base_name") { put("type", "string") }
                                    putJsonObject("category") { stringEnum(CATEGORY_VALUES) }
                                    putJsonObject("note") { put("type", "string") }
                                    putJsonObject("raw") { put("type", "string") }
                                }
                                putJsonArray("required") {
                                    add(JsonPrimitive("quantity"))
                                    add(JsonPrimitive("quantity_max"))
                                    add(JsonPrimitive("unit"))
                                    add(JsonPrimitive("name"))
                                    add(JsonPrimitive("base_name"))
                                    add(JsonPrimitive("category"))
                                    add(JsonPrimitive("note"))
                                    add(JsonPrimitive("raw"))
                                }
                                put("additionalProperties", false)
                            }
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("name"))
                        add(JsonPrimitive("items"))
                    }
                    put("additionalProperties", false)
                }
            }
            putJsonObject("steps") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("text") { put("type", "string") }
                        putJsonObject("timer_seconds") { put("type", "integer") }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("text"))
                        add(JsonPrimitive("timer_seconds"))
                    }
                    put("additionalProperties", false)
                }
            }
            putJsonObject("equipment") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("consumables") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("tags") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("new_list_suggestions") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("kind") { stringEnum(SUGGESTION_KIND_VALUES) }
                        putJsonObject("name") { put("type", "string") }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("kind"))
                        add(JsonPrimitive("name"))
                    }
                    put("additionalProperties", false)
                }
            }
            putJsonObject("confidence") { put("type", "number") }
        }
        putJsonArray("required") {
            listOf(
                "is_recipe", "title", "description", "servings", "prep_minutes",
                "cook_minutes", "total_minutes", "cuisine", "complexity",
                "complexity_reason", "spice_level", "diet_type", "contains",
                "kcal_per_serving", "make_ahead", "freezer_friendly",
                "ingredient_groups", "steps", "equipment", "consumables",
                "tags", "new_list_suggestions", "confidence"
            ).forEach { add(JsonPrimitive(it)) }
        }
        put("additionalProperties", false)
    }

    private fun JsonObjectBuilder.stringEnum(values: List<String>) {
        put("type", "string")
        putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
    }

    private fun validateAndMap(
        raw: RawExtractedRecipe,
        input: ExtractionInput,
        usage: LlmUsage
    ): Result<ExtractedRecipe> {
        val totalIngredients = raw.ingredient_groups.sumOf { it.items.size }
        val totalSteps = raw.steps.size

        if (!raw.is_recipe || (totalIngredients == 0 && totalSteps == 0)) {
            return Result.failure(NoRecipeFoundException("No recipe found in source", usage))
        }

        val servings = if (raw.servings > 0) raw.servings.coerceIn(1, 50) else null
        val prepMinutes = if (raw.prep_minutes > 0) raw.prep_minutes.coerceIn(0, 1440) else null
        val cookMinutes = if (raw.cook_minutes > 0) raw.cook_minutes.coerceIn(0, 1440) else null
        val totalMinutes = if (raw.total_minutes > 0) raw.total_minutes.coerceIn(0, 1440) else null
        val kcal = if (raw.kcal_per_serving > 0) raw.kcal_per_serving else null

        val filteredTags = filterAllowed(raw.tags, input.allowedTags)
        val filteredDiet = filterAllowed(raw.contains, input.allowedDiet)
        val filteredEquipment = filterAllowed(raw.equipment, input.allowedEquipment)
        val filteredConsumables = filterAllowed(raw.consumables, input.allowedConsumables)

        val groups = raw.ingredient_groups.map { group ->
            ExtractedIngredientGroup(
                name = group.name.toNullIfBlankOrUnknown(),
                items = group.items.map { item ->
                    ExtractedIngredient(
                        quantity = if (item.quantity > 0.0) item.quantity else null,
                        quantityMax = if (item.quantity_max > 0.0) item.quantity_max else null,
                        unit = item.unit.toNullIfBlankOrUnknown(),
                        name = item.name.ifBlank { item.raw },
                        baseName = item.base_name.ifBlank { item.name },
                        category = item.category.toAllowedEnum(CATEGORY_VALUES) ?: "other",
                        note = item.note.toNullIfBlankOrUnknown(),
                        rawText = item.raw.ifBlank { "${item.quantity} ${item.unit} ${item.name}".trim() }
                    )
                }
            )
        }

        val steps = raw.steps.map { step ->
            ExtractedStep(
                text = step.text,
                timerSeconds = if (step.timer_seconds > 0) step.timer_seconds else null
            )
        }

        val suggestions = raw.new_list_suggestions.mapNotNull {
            val kind = it.kind.toAllowedEnum(SUGGESTION_KIND_VALUES)
            val name = it.name.trim()
            if (kind == null || name.isEmpty()) null else NewListSuggestion(kind = kind, name = name)
        }

        val recipe = ExtractedRecipe(
            isRecipe = true,
            title = raw.title.toNullIfBlankOrUnknown(),
            description = raw.description.toNullIfBlankOrUnknown(),
            servings = servings,
            prepMinutes = prepMinutes,
            cookMinutes = cookMinutes,
            totalMinutes = totalMinutes,
            cuisine = raw.cuisine.toNullIfBlankOrUnknown(),
            complexity = raw.complexity.toAllowedEnum(COMPLEXITY_VALUES),
            complexityReason = raw.complexity_reason.toNullIfBlankOrUnknown(),
            spiceLevel = raw.spice_level.toAllowedEnum(SPICE_LEVEL_VALUES),
            dietType = raw.diet_type.toAllowedEnum(DIET_TYPE_VALUES),
            contains = filteredDiet,
            kcalPerServing = kcal,
            makeAhead = raw.make_ahead,
            freezerFriendly = raw.freezer_friendly,
            ingredientGroups = groups,
            steps = steps,
            equipment = filteredEquipment,
            consumables = filteredConsumables,
            tags = filteredTags,
            newListSuggestions = suggestions,
            confidence = raw.confidence,
            usage = usage
        )

        return Result.success(recipe)
    }

    private fun filterAllowed(items: List<String>, allowed: List<String>): List<String> {
        if (allowed.isEmpty()) return emptyList()
        val allowedMap = allowed.associateBy { it.lowercase().trim() }
        return items.mapNotNull { allowedMap[it.lowercase().trim()] }.distinct()
    }

    private fun String.toNullIfBlankOrUnknown(): String? {
        val trimmed = this.trim()
        return if (trimmed.isEmpty() || trimmed.equals(UNKNOWN, ignoreCase = true)) null else trimmed
    }

    // Structured outputs don't guarantee enum capitalisation, so match case-insensitively.
    private fun String.toAllowedEnum(allowed: List<String>): String? {
        val normalized = trim().lowercase()
        return allowed.firstOrNull { it == normalized }
    }
}


@Serializable
internal data class RawExtractedRecipe(
    val is_recipe: Boolean = false,
    val title: String = "",
    val description: String = "",
    val servings: Int = 0,
    val prep_minutes: Int = 0,
    val cook_minutes: Int = 0,
    val total_minutes: Int = 0,
    val cuisine: String = "",
    val complexity: String = "",
    val complexity_reason: String = "",
    val spice_level: String = "",
    val diet_type: String = "",
    val contains: List<String> = emptyList(),
    val kcal_per_serving: Int = 0,
    val make_ahead: Boolean = false,
    val freezer_friendly: Boolean = false,
    val ingredient_groups: List<RawIngredientGroup> = emptyList(),
    val steps: List<RawStep> = emptyList(),
    val equipment: List<String> = emptyList(),
    val consumables: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val new_list_suggestions: List<RawNewListSuggestion> = emptyList(),
    val confidence: Double = 0.0
)

@Serializable
internal data class RawIngredientGroup(
    val name: String = "",
    val items: List<RawIngredientItem> = emptyList()
)

@Serializable
internal data class RawIngredientItem(
    val quantity: Double = 0.0,
    val quantity_max: Double = 0.0,
    val unit: String = "",
    val name: String = "",
    val base_name: String = "",
    val category: String = "",
    val note: String = "",
    val raw: String = ""
)

@Serializable
internal data class RawStep(
    val text: String = "",
    val timer_seconds: Int = 0
)

@Serializable
internal data class RawNewListSuggestion(
    val kind: String = "",
    val name: String = ""
)
