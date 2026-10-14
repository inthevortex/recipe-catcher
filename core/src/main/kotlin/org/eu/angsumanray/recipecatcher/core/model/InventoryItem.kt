package org.eu.angsumanray.recipecatcher.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class InventoryStatus(val value: String) {
    IN_STOCK("in_stock"),
    LOW("low"),
    OUT("out");

    companion object {
        fun from(value: String): InventoryStatus {
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: IN_STOCK
        }
    }
}

@Serializable
data class InventoryItem(
    val id: Long = 0L,
    val name: String,
    val baseName: String,
    val category: String,
    val status: String, // "in_stock", "low", "out"
    val updatedAt: Long = System.currentTimeMillis()
)
