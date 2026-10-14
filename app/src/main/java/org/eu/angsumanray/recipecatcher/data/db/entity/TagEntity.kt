package org.eu.angsumanray.recipecatcher.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tag",
    indices = [
        Index(value = ["kind", "name"], unique = true)
    ]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val kind: String, // tag, diet, equipment, consumable
    @ColumnInfo(collate = ColumnInfo.NOCASE)
    val name: String,
    val position: Int = 0
) {
    companion object {
        const val KIND_TAG = "tag"
        const val KIND_DIET = "diet"
        const val KIND_EQUIPMENT = "equipment"
        const val KIND_CONSUMABLE = "consumable"
    }
}
