package org.eu.angsumanray.recipecatcher.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class GrocerySource(val value: String) {
    PLAN("plan"),
    INVENTORY("inventory"),
    MANUAL("manual");

    companion object {
        fun from(value: String): GrocerySource {
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: MANUAL
        }
    }
}

@Serializable
data class GroceryItem(
    val id: Long = 0L,
    val name: String,
    val baseName: String? = null,
    val category: String,
    val quantityText: String? = null,
    val source: String, // "plan", "inventory", "manual"
    val weekStart: String? = null,
    val checked: Boolean = false,
    val position: Int = 0
)
