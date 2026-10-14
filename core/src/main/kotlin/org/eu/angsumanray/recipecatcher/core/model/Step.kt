package org.eu.angsumanray.recipecatcher.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Step(
    val id: Long = 0L,
    val recipeId: Long = 0L,
    val position: Int = 0,
    val text: String,
    val timerSeconds: Int? = null
)
