package org.eu.angsumanray.recipecatcher.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ingredient",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipe_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["recipe_id"])
    ]
)
data class IngredientEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "recipe_id")
    val recipeId: Long,
    val position: Int,
    @ColumnInfo(name = "group_name")
    val groupName: String? = null,
    val quantity: Double? = null,
    @ColumnInfo(name = "quantity_max")
    val quantityMax: Double? = null,
    val unit: String? = null,
    val name: String,
    @ColumnInfo(name = "base_name")
    val baseName: String,
    val category: String, // vegetables, fruit, dairy, meat & fish, grains & flours, spices, pantry, other
    val note: String? = null,
    @ColumnInfo(name = "raw_text")
    val rawText: String
)
