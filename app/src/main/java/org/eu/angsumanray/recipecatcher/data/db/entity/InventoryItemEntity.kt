package org.eu.angsumanray.recipecatcher.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "inventory_item",
    indices = [
        Index(value = ["base_name"], unique = true)
    ]
)
data class InventoryItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "base_name")
    val baseName: String,
    val category: String,
    val status: String = STATUS_IN_STOCK, // in_stock, low, out
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_IN_STOCK = "in_stock"
        const val STATUS_LOW = "low"
        const val STATUS_OUT = "out"
    }
}
