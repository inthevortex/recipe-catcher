package org.eu.angsumanray.recipecatcher.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PlanSlot(
    val id: Long = 0L,
    val date: String, // ISO-8601 text "YYYY-MM-DD"
    val name: String,
    val time: String? = null, // "HH:mm"
    val position: Int = 0
)
