package org.eu.angsumanray.recipecatcher.core.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AnthropicMessageResponse(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val content: List<AnthropicContentBlock> = emptyList(),
    val model: String? = null,
    @SerialName("stop_reason")
    val stopReason: String? = null,
    val usage: AnthropicUsage? = null
)

@Serializable
data class AnthropicContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonElement? = null,
    @SerialName("tool_use_id")
    val toolUseId: String? = null,
    val content: JsonElement? = null
)

@Serializable
data class AnthropicUsage(
    @SerialName("input_tokens")
    val inputTokens: Int = 0,
    @SerialName("output_tokens")
    val outputTokens: Int = 0,
    @SerialName("cache_read_input_tokens")
    val cacheReadInputTokens: Int? = null,
    @SerialName("web_search_requests")
    val webSearchRequests: Int? = null
)
