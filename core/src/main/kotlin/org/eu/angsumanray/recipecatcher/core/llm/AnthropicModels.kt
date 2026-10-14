package org.eu.angsumanray.recipecatcher.core.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import java.io.IOException

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
    @SerialName("server_tool_use")
    val serverToolUse: AnthropicServerToolUsage? = null
)

@Serializable
data class AnthropicServerToolUsage(
    @SerialName("web_search_requests")
    val webSearchRequests: Int = 0
)

/** Plumbing shared by the chat, web search and planning calls. */
internal object AnthropicSupport {

    /** Maps a non-2xx status to the same exception types the extractor uses. */
    fun httpError(statusCode: Int, body: String, modelId: String): Exception = when (statusCode) {
        401, 403 -> ApiKeyInvalidException("Check API key: HTTP $statusCode")
        404 -> ModelNotFoundException("Model not found: HTTP 404 for model $modelId")
        429, 529 -> TransientApiException("Transient API error: HTTP $statusCode", statusCode)
        in 500..599 -> TransientApiException("Server error: HTTP $statusCode", statusCode)
        else -> RuntimeException("Anthropic API error: HTTP $statusCode ${body.take(300)}")
    }

    /** Joins every text block: an answer with citations arrives split across several. */
    fun responseText(response: AnthropicMessageResponse): String =
        response.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("")

    private val fenceRegex = Regex("```(?:json)?[ \\t]*\\n?(.*?)```", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** Pulls the JSON payload out of a reply that may wrap it in a code fence or surrounding prose. */
    fun extractJsonBlock(text: String): String {
        fenceRegex.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        val trimmed = text.trim()
        val firstObj = trimmed.indexOf('{')
        val lastObj = trimmed.lastIndexOf('}')
        val firstArr = trimmed.indexOf('[')
        val lastArr = trimmed.lastIndexOf(']')

        if (firstArr != -1 && lastArr > firstArr && (firstObj == -1 || firstArr < firstObj)) {
            return trimmed.substring(firstArr, lastArr + 1)
        }
        if (firstObj != -1 && lastObj > firstObj) {
            return trimmed.substring(firstObj, lastObj + 1)
        }
        return trimmed
    }
}

/** A short message for a failed chat, web search or planning call, fit to show in the UI. */
fun llmErrorMessage(error: Throwable?): String = when (error) {
    is ApiKeyInvalidException -> "Add or check your Anthropic API key in Settings."
    is ModelNotFoundException -> "Model not found. Update the model ID in Settings."
    is TransientApiException -> "Claude is busy right now. Try again in a moment."
    is IOException -> "No connection. Check your network and try again."
    is SerializationException ->"Couldn't read Claude's answer. Try again."
    else -> "Something went wrong. Try again."
}
