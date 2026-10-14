package org.eu.angsumanray.recipecatcher.core.model

import kotlinx.serialization.Serializable

@Serializable
data class CookLogEntry(
    val id: Long = 0L,
    val recipeId: Long = 0L,
    val cookedOn: String, // ISO-8601 text "YYYY-MM-DD"
    val note: String? = null,
    val photoPath: String? = null
)
