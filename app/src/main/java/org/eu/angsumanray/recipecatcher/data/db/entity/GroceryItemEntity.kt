package org.eu.angsumanray.recipecatcher.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(
    tableName = "grocery_item",
    indices = [
        Index(value = ["week_start"]),
        Index(value = ["checked"])
    ]
)
data class GroceryItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "base_name")
    val baseName: String? = null,
    val category: String,
    @ColumnInfo(name = "quantity_text")
    val quantityText: String? = null,
    val source: String = SOURCE_MANUAL, // plan, inventory, manual
    @ColumnInfo(name = "week_start")
    val weekStart: LocalDate? = null,
    val checked: Boolean = false,
    val position: Int = 0
) {
    companion object {
        const val SOURCE_PLAN = "plan"
        const val SOURCE_INVENTORY = "inventory"
        const val SOURCE_MANUAL = "manual"
    }
}
