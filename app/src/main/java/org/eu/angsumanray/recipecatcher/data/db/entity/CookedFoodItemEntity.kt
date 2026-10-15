package org.eu.angsumanray.recipecatcher.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cooked_food_item",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipe_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["recipe_id"])
    ]
)
data class CookedFoodItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "recipe_id")
    val recipeId: Long? = null,
    val portions: Int = 1,
    @ColumnInfo(name = "date_cooked")
    val dateCooked: String, // "YYYY-MM-DD"
    val notes: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
