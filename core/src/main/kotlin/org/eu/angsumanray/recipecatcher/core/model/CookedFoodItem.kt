package org.eu.angsumanray.recipecatcher.core.model

import kotlinx.serialization.Serializable

@Serializable
data class CookedFoodItem(
    val id: Long = 0L,
    val name: String,
    val recipeId: Long? = null,
    val portions: Int = 1,
    val dateCooked: String, // "YYYY-MM-DD"
    val notes: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
