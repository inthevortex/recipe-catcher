package org.eu.angsumanray.recipecatcher.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recipe",
    indices = [
        Index(value = ["source_key"], unique = true),
        Index(value = ["status"])
    ]
)
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "source_type")
    val sourceType: String, // instagram, web, youtube, manual
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "source_url")
    val sourceUrl: String? = null,
    @ColumnInfo(name = "author_handle")
    val authorHandle: String? = null,
    @ColumnInfo(name = "caption_raw")
    val captionRaw: String? = null,
    @ColumnInfo(name = "comment_raw")
    val commentRaw: String? = null,
    val title: String? = null,
    val description: String? = null,
    val servings: Int? = null,
    @ColumnInfo(name = "prep_min")
    val prepMin: Int? = null,
    @ColumnInfo(name = "cook_min")
    val cookMin: Int? = null,
    @ColumnInfo(name = "total_min")
    val totalMin: Int? = null,
    val cuisine: String? = null,
    val complexity: String? = null, // easy, medium, hard
    @ColumnInfo(name = "complexity_reason")
    val complexityReason: String? = null,
    @ColumnInfo(name = "spice_level")
    val spiceLevel: String? = null, // mild, medium, hot
    @ColumnInfo(name = "diet_type")
    val dietType: String? = null, // vegan, vegetarian, eggetarian, non-veg
    @ColumnInfo(name = "kcal_per_serving")
    val kcalPerServing: Int? = null,
    @ColumnInfo(name = "make_ahead")
    val makeAhead: Boolean = false,
    @ColumnInfo(name = "freezer_friendly")
    val freezerFriendly: Boolean = false,
    @ColumnInfo(name = "is_favourite")
    val isFavourite: Boolean = false,
    @ColumnInfo(name = "personal_note")
    val personalNote: String? = null,
    val reviewed: Boolean = true,
    @ColumnInfo(name = "image_path")
    val imagePath: String? = null,
    @ColumnInfo(name = "cover_photo_path")
    val coverPhotoPath: String? = null,
    val status: String = STATUS_PENDING_FETCH,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "llm_model")
    val llmModel: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "saved_at")
    val savedAt: Long? = null
) {
    companion object {
        const val STATUS_PENDING_FETCH = "pending_fetch"
        const val STATUS_PENDING_EXTRACT = "pending_extract"
        const val STATUS_NEEDS_REVIEW = "needs_review"
        const val STATUS_SAVED = "saved"
        const val STATUS_FAILED_FETCH = "failed_fetch"
        const val STATUS_FAILED_EXTRACT = "failed_extract"
        const val STATUS_NO_RECIPE_FOUND = "no_recipe_found"
    }
}
