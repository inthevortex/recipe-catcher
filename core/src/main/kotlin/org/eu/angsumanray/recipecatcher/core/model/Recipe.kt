package org.eu.angsumanray.recipecatcher.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class RecipeStatus(val value: String) {
    PENDING_FETCH("pending_fetch"),
    PENDING_EXTRACT("pending_extract"),
    NEEDS_REVIEW("needs_review"),
    SAVED("saved"),
    FAILED_FETCH("failed_fetch"),
    FAILED_EXTRACT("failed_extract"),
    NO_RECIPE_FOUND("no_recipe_found");

    companion object {
        fun from(value: String): RecipeStatus {
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: PENDING_FETCH
        }
    }
}

@Serializable
data class Recipe(
    val id: Long = 0L,
    val sourceType: SourceType,
    val sourceKey: String,
    val sourceUrl: String? = null,
    val authorHandle: String? = null,
    val captionRaw: String? = null,
    val commentRaw: String? = null,
    val title: String? = null,
    val description: String? = null,
    val servings: Int? = null,
    val prepMin: Int? = null,
    val cookMin: Int? = null,
    val totalMin: Int? = null,
    val cuisine: String? = null,
    val complexity: String? = null, // "easy", "medium", "hard"
    val complexityReason: String? = null,
    val spiceLevel: String? = null, // "mild", "medium", "hot"
    val dietType: String? = null, // "vegan", "vegetarian", "eggetarian", "non-veg"
    val kcalPerServing: Int? = null,
    val makeAhead: Boolean = false,
    val freezerFriendly: Boolean = false,
    val isFavourite: Boolean = false,
    val personalNote: String? = null,
    val reviewed: Boolean = true,
    val imagePath: String? = null,
    val coverPhotoPath: String? = null,
    val status: RecipeStatus = RecipeStatus.PENDING_FETCH,
    val errorMessage: String? = null,
    val llmModel: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val savedAt: Long? = null,
    val ingredients: List<Ingredient> = emptyList(),
    val steps: List<Step> = emptyList(),
    val tags: List<Tag> = emptyList()
)
