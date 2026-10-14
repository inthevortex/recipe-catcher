package org.eu.angsumanray.recipecatcher.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "plan_entry",
    foreignKeys = [
        ForeignKey(
            entity = PlanSlotEntity::class,
            parentColumns = ["id"],
            childColumns = ["slot_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipe_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = PlanEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["from_entry_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["slot_id"]),
        Index(value = ["recipe_id"]),
        Index(value = ["from_entry_id"])
    ]
)
data class PlanEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "slot_id")
    val slotId: Long,
    val kind: String, // cook, prep, leftover
    @ColumnInfo(name = "recipe_id")
    val recipeId: Long? = null,
    val text: String? = null,
    val portions: Int = 1,
    @ColumnInfo(name = "portions_eaten_now")
    val portionsEatenNow: Int = 1,
    @ColumnInfo(name = "from_entry_id")
    val fromEntryId: Long? = null,
    @ColumnInfo(name = "prep_night_before")
    val prepNightBefore: Boolean = false,
    @ColumnInfo(name = "done_at")
    val doneAt: Long? = null,
    val position: Int = 0
) {
    companion object {
        const val KIND_COOK = "cook"
        const val KIND_PREP = "prep"
        const val KIND_LEFTOVER = "leftover"
    }
}
