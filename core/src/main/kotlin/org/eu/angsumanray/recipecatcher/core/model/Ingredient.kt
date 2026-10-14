package org.eu.angsumanray.recipecatcher.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Ingredient(
    val id: Long = 0L,
    val recipeId: Long = 0L,
    val position: Int = 0,
    val groupName: String? = null,
    val quantity: Double? = null,
    val quantityMax: Double? = null,
    val unit: String? = null,
    val name: String,
    val baseName: String,
    val category: String,
    val note: String? = null,
    val rawText: String = ""
)
