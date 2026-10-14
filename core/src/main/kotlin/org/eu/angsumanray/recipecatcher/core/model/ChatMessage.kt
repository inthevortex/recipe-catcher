package org.eu.angsumanray.recipecatcher.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: Long = 0L,
    val sessionId: Long = 0L,
    val role: String, // "user", "assistant"
    val text: String,
    val includeWeb: Boolean = false,
    val suggestionsJson: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
