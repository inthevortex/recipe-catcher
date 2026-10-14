package org.eu.angsumanray.recipecatcher.core.llm

import kotlinx.serialization.Serializable
import org.eu.angsumanray.recipecatcher.core.model.SourceType

data class ExtractionInput(
    val sourceType: SourceType,
    val sourceText: String,
    val topComment: String? = null,
    val author: String? = null,
    val allowedTags: List<String> = emptyList(),
    val allowedDiet: List<String> = emptyList(),
    val allowedEquipment: List<String> = emptyList(),
    val allowedConsumables: List<String> = emptyList(),
    val allowNewTags: Boolean = false
)

data class ExtractedIngredient(
    val quantity: Double?,
    val quantityMax: Double?,
    val unit: String?,
    val name: String,
    val baseName: String,
    val category: String,
    val note: String?,
    val rawText: String
)

data class ExtractedIngredientGroup(
    val name: String?,
    val items: List<ExtractedIngredient>
)

data class ExtractedStep(
    val text: String,
    val timerSeconds: Int?
)

data class NewListSuggestion(
    val kind: String,
    val name: String
)

@Serializable
data class LlmUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val webSearches: Int = 0,
    val model: String? = null
)

data class ExtractedRecipe(
    val isRecipe: Boolean,
    val title: String?,
    val description: String?,
    val servings: Int?,
    val prepMinutes: Int?,
    val cookMinutes: Int?,
    val totalMinutes: Int?,
    val cuisine: String?,
    val complexity: String?,
    val complexityReason: String?,
    val spiceLevel: String?,
    val dietType: String?,
    val contains: List<String>,
    val kcalPerServing: Int?,
    val makeAhead: Boolean,
    val freezerFriendly: Boolean,
    val ingredientGroups: List<ExtractedIngredientGroup>,
    val steps: List<ExtractedStep>,
    val equipment: List<String>,
    val consumables: List<String>,
    val tags: List<String>,
    val newListSuggestions: List<NewListSuggestion>,
    val confidence: Double,
    val usage: LlmUsage = LlmUsage(0, 0, 0)
)

interface RecipeExtractor {
    suspend fun extract(input: ExtractionInput): Result<ExtractedRecipe>
}

class ApiKeyInvalidException(message: String) : Exception(message)
class ModelNotFoundException(message: String) : Exception(message)
class TransientApiException(message: String, val statusCode: Int) : Exception(message)
class NoRecipeFoundException(message: String, val usage: LlmUsage? = null) : Exception(message)
