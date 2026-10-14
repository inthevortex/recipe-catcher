package org.eu.angsumanray.recipecatcher.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class PlanEntryKind(val value: String) {
    COOK("cook"),
    PREP("prep"),
    LEFTOVER("leftover");

    companion object {
        fun from(value: String): PlanEntryKind {
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: COOK
        }
    }
}

@Serializable
data class PlanEntry(
    val id: Long = 0L,
    val slotId: Long = 0L,
    val kind: String, // "cook", "prep", "leftover"
    val recipeId: Long? = null,
    val text: String? = null,
    val portions: Int = 1,
    val portionsEatenNow: Int = 1,
    val fromEntryId: Long? = null,
    val prepNightBefore: Boolean = false,
    val doneAt: Long? = null,
    val position: Int = 0
)
