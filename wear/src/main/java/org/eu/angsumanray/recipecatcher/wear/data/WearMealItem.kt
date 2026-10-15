package org.eu.angsumanray.recipecatcher.wear.data

import kotlinx.serialization.Serializable

@Serializable
data class WearMealItem(
    val id: Long,
    val title: String,
    val kind: String,
    val slot: String,
    val time: String? = null,
    val done: Boolean = false
)

@Serializable
data class WearTodaySnapshot(
    val dateIso: String = "",
    val timestamp: Long = 0L,
    val items: List<WearMealItem> = emptyList()
)
