package org.eu.angsumanray.recipecatcher.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.eu.angsumanray.recipecatcher.core.model.ChatMessage
import org.eu.angsumanray.recipecatcher.core.ports.SearchResult
import org.eu.angsumanray.recipecatcher.core.ports.WebSearchProvider
import java.net.URI

@Serializable
data class RecipeIndexEntry(
    val id: Long,
    val title: String,
    val tags: List<String> = emptyList(),
    val mainIngredients: List<String> = emptyList(),
    val totalMinutes: Int? = null,
    val complexity: String? = null,
    val spiceLevel: String? = null,
    val dietType: String? = null,
    val contains: List<String> = emptyList(),
    val kcalPerServing: Int? = null,
    val makeAhead: Boolean = false,
    val freezerFriendly: Boolean = false,
    val equipment: List<String> = emptyList(),
    val favourite: Boolean = false,
    val lastCookedDate: String? = null
) {
    fun toIndexLine(): String {
        val makeAheadFreezer = when {
            makeAhead && freezerFriendly -> "make-ahead, freezer"
            makeAhead -> "make-ahead"
            freezerFriendly -> "freezer"
            else -> "none"
        }
        return listOf(
            id.toString(),
            title,
            tags.joinToString(";").ifEmpty { "none" },
            mainIngredients.take(8).joinToString(";").ifEmpty { "none" },
            totalMinutes?.let { "${it}m" } ?: "unknown",
            complexity ?: "unknown",
            spiceLevel ?: "unknown",
            dietType ?: "unknown",
            contains.joinToString(";").ifEmpty { "none" },
            kcalPerServing?.let { "${it}kcal" } ?: "unknown",
            makeAheadFreezer,
            equipment.joinToString(";").ifEmpty { "none" },
            if (favourite) "favourite" else "standard",
            lastCookedDate ?: "never"
        ).joinToString(" | ")
    }
}

@Serializable
data class RecipeSuggestion(
    @SerialName("recipe_id") val recipeId: Long,
    val reason: String = "",
    val missing: List<String> = emptyList()
)

@Serializable
data class WebRecipeSuggestion(
    val title: String,
    val url: String,
    val site: String = "",
    val reason: String = ""
)

@Serializable
data class ChatPlanResult(
    val reply: String,
    val suggestions: List<RecipeSuggestion> = emptyList(),
    @SerialName("follow_up_question") val followUpQuestion: String = "",
    val webSuggestions: List<WebRecipeSuggestion> = emptyList(),
    val usage: LlmUsage = LlmUsage(0, 0, 0)
)

class ChatPlanner(
    private val httpClient: HttpClient,
    private val apiKeyProvider: suspend () -> String,
    private val modelIdProvider: suspend () -> String = { ClaudeExtractor.DEFAULT_MODEL_ID },
    private val webSearchProvider: WebSearchProvider? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {

    companion object {
        const val MAX_SUGGESTIONS = 5
        const val MAX_WEB_PICKS = 3
        const val HISTORY_LIMIT = 10
        const val PRE_FILTER_THRESHOLD = 300
        const val PRE_FILTER_CAP = 150

        const val LIBRARY_SYSTEM_PROMPT = """You are a helpful culinary assistant for a personal offline recipe app.
Suggest at most 5 recipes, ONLY by recipe_id from the provided recipe index.
Weigh ingredients on hand, mood, time, spice preference, diet and "without" requests ("veg, no dairy"), lighter or heavier meals by calories, equipment the user has or lacks ("no oven today"), whether they want something to make ahead, favourites, and how long since each was cooked.
List missing ingredients for suggested recipes.
If the user's request is too vague, ask ONE concise follow-up question.
If the user asks a question about cooking techniques, ingredients, or variations, answer clearly and constructively in the reply.

You MUST respond strictly with a valid JSON object adhering to this schema:
{
  "reply": "Assistant message answering the user's question and introducing suggestions...",
  "suggestions": [
    {
      "recipe_id": 42,
      "reason": "Brief reason why this recipe fits",
      "missing": ["list", "of", "missing", "ingredients"]
    }
  ],
  "follow_up_question": ""
}"""

        const val WEB_SYSTEM_PROMPT = """You are a culinary web search assistant.
Find up to 3 candidate web recipes that best match the user's request.
Requirements:
1. Candidate pages must contain a full written recipe, NOT video links (no youtube, vimeo, tiktok).
2. For each pick, provide the title, full url (strictly matching one of the provided search results), site name, and a concise reason why it fits.
Output ONLY a JSON array in a ```json ... ``` code fence with this schema:
```json
[
  {
    "title": "Recipe Title",
    "url": "https://example.com/recipe",
    "site": "example.com",
    "reason": "Why this recipe fits"
  }
]
```"""
    }

    suspend fun planChat(
        userMessage: String,
        history: List<ChatMessage> = emptyList(),
        libraryRecipes: List<RecipeIndexEntry>,
        includeWeb: Boolean = false,
        maxWebSearches: Int = 3
    ): Result<ChatPlanResult> {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return Result.failure(ApiKeyInvalidException("API key is empty"))
        }
        val modelId = modelIdProvider().trim().ifEmpty { ClaudeExtractor.DEFAULT_MODEL_ID }

        // 1. Pre-filter library recipes if above 300
        val filteredRecipes = prefilterRecipes(libraryRecipes, userMessage)

        // 2. Perform library call
        val libraryResult = callLibraryLlm(apiKey, modelId, userMessage, history, filteredRecipes)
        if (libraryResult.isFailure) {
            return Result.failure(libraryResult.exceptionOrNull() ?: RuntimeException("Library call failed"))
        }

        val basePlan = libraryResult.getOrThrow()

        // 3. Web call (if requested)
        if (!includeWeb) {
            return Result.success(basePlan)
        }

        val webPicksResult = callWebSearchLlm(apiKey, modelId, userMessage, maxWebSearches)
        return if (webPicksResult.isSuccess) {
            val (webSuggestions, webUsage) = webPicksResult.getOrThrow()
            Result.success(
                basePlan.copy(
                    webSuggestions = webSuggestions,
                    usage = LlmUsage(
                        inputTokens = basePlan.usage.inputTokens + webUsage.inputTokens,
                        outputTokens = basePlan.usage.outputTokens + webUsage.outputTokens,
                        cacheReadTokens = basePlan.usage.cacheReadTokens + webUsage.cacheReadTokens,
                        webSearches = basePlan.usage.webSearches + webUsage.webSearches,
                        model = modelId
                    )
                )
            )
        } else {
            // If web call fails, still return library results gracefully
            Result.success(basePlan)
        }
    }

    suspend fun findWebCandidates(
        dishName: String,
        hints: String = "",
        maxResults: Int = MAX_WEB_PICKS
    ): Result<List<WebRecipeSuggestion>> {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return Result.failure(ApiKeyInvalidException("API key is empty"))
        }
        val modelId = modelIdProvider().trim().ifEmpty { ClaudeExtractor.DEFAULT_MODEL_ID }
        val query = if (hints.isNotBlank()) "$dishName ($hints)" else dishName

        val result = callWebSearchLlm(apiKey, modelId, query, maxResults)
        return result.map { it.first }
    }

    internal fun prefilterRecipes(
        recipes: List<RecipeIndexEntry>,
        query: String,
        cap: Int = PRE_FILTER_CAP
    ): List<RecipeIndexEntry> {
        if (recipes.size <= PRE_FILTER_THRESHOLD) return recipes

        val queryWords = query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .toSet()

        if (queryWords.isEmpty()) return recipes.take(cap)

        fun score(recipe: RecipeIndexEntry): Int {
            var score = 0
            val titleWords = recipe.title.lowercase().split(Regex("[^a-z0-9]+")).toSet()
            val tagWords = recipe.tags.flatMap { it.lowercase().split(Regex("[^a-z0-9]+")) }.toSet()
            val ingWords = recipe.mainIngredients.flatMap { it.lowercase().split(Regex("[^a-z0-9]+")) }.toSet()

            for (w in queryWords) {
                if (titleWords.contains(w)) score += 5
                if (ingWords.contains(w)) score += 3
                if (tagWords.contains(w)) score += 2
            }
            if (recipe.favourite) score += 1
            return score
        }

        return recipes.sortedByDescending { score(it) }.take(cap)
    }

    private suspend fun callLibraryLlm(
        apiKey: String,
        modelId: String,
        userMessage: String,
        history: List<ChatMessage>,
        recipes: List<RecipeIndexEntry>
    ): Result<ChatPlanResult> {
        val validIds = recipes.map { it.id }.toSet()
        val indexText = buildString {
            appendLine("id | title | tags | main ingredients | total min | complexity | spice | diet | contains | kcal | make-ahead / freezer | equipment | favourite | last cooked")
            recipes.forEach { appendLine(it.toIndexLine()) }
        }

        val systemText = "$LIBRARY_SYSTEM_PROMPT\n\nRecipe Index (${recipes.size} recipes):\n$indexText"

        val recentHistory = history.takeLast(HISTORY_LIMIT)
        val messagesPayload = buildJsonArray {
            for (msg in recentHistory) {
                val role = if (msg.role.equals("user", ignoreCase = true)) "user" else "assistant"
                add(buildJsonObject {
                    put("role", role)
                    put("content", msg.text)
                })
            }
            add(buildJsonObject {
                put("role", "user")
                put("content", userMessage)
            })
        }

        val requestPayload = buildJsonObject {
            put("model", modelId)
            put("max_tokens", 1500)
            putJsonArray("system") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", systemText)
                })
            }
            put("messages", messagesPayload)
        }.toString()

        return try {
            val response = httpClient.post(ClaudeExtractor.ANTHROPIC_MESSAGES_URL) {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", ClaudeExtractor.ANTHROPIC_VERSION)
                setBody(requestPayload)
            }

            val statusCode = response.status.value
            val responseBody = response.bodyAsText()

            if (statusCode !in 200..299) {
                return Result.failure(RuntimeException("Anthropic API error: HTTP $statusCode $responseBody"))
            }

            val anthropicResponse = json.decodeFromString<AnthropicMessageResponse>(responseBody)
            val textContent = anthropicResponse.content.firstOrNull { it.type == "text" }?.text
                ?: return Result.failure(RuntimeException("No text content returned from Claude"))

            val parsedJson = extractJsonBlock(textContent)
            val rawResult = json.decodeFromString<RawChatPlanResponse>(parsedJson)

            // Validate suggestions: drop IDs not in index, cap at MAX_SUGGESTIONS
            val validatedSuggestions = rawResult.suggestions
                .filter { it.recipeId in validIds }
                .take(MAX_SUGGESTIONS)

            val usage = LlmUsage(
                inputTokens = anthropicResponse.usage?.inputTokens ?: 0,
                outputTokens = anthropicResponse.usage?.outputTokens ?: 0,
                cacheReadTokens = anthropicResponse.usage?.cacheReadInputTokens ?: 0,
                model = anthropicResponse.model ?: modelId
            )

            Result.success(
                ChatPlanResult(
                    reply = rawResult.reply.ifBlank { textContent },
                    suggestions = validatedSuggestions,
                    followUpQuestion = rawResult.followUpQuestion,
                    webSuggestions = emptyList(),
                    usage = usage
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun callWebSearchLlm(
        apiKey: String,
        modelId: String,
        userQuery: String,
        maxWebSearches: Int
    ): Result<Pair<List<WebRecipeSuggestion>, LlmUsage>> {
        // If webSearchProvider is provided, prioritize it
        if (webSearchProvider != null) {
            val providerResult = callProviderWebSearch(apiKey, modelId, userQuery, maxWebSearches, webSearchProvider)
            if (providerResult.isSuccess) {
                return providerResult
            }
        }

        // Otherwise try server-side Anthropic web_search tool
        return tryServerSideWebSearch(apiKey, modelId, userQuery, maxWebSearches)
    }

    private suspend fun tryServerSideWebSearch(
        apiKey: String,
        modelId: String,
        userQuery: String,
        maxWebSearches: Int
    ): Result<Pair<List<WebRecipeSuggestion>, LlmUsage>> {
        return try {
            val userContent = "Find candidate recipes for: $userQuery. Return up to 3 pages with full recipes, not videos."
            val messages = mutableListOf<JsonObject>()
            messages.add(buildJsonObject {
                put("role", "user")
                put("content", userContent)
            })

            val toolsArray = buildJsonArray {
                add(buildJsonObject {
                    put("type", "web_search_20260209")
                    put("name", "web_search")
                    put("max_uses", maxWebSearches)
                })
            }

            var iterations = 0
            var finalResponse: AnthropicMessageResponse? = null
            var totalInputTokens = 0
            var totalOutputTokens = 0
            var totalCacheRead = 0
            val searchResultUrls = mutableSetOf<String>()

            while (iterations < 5) {
                iterations++
                val requestPayload = buildJsonObject {
                    put("model", modelId)
                    put("max_tokens", 1500)
                    putJsonArray("system") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", WEB_SYSTEM_PROMPT)
                        })
                    }
                    put("messages", JsonArray(messages))
                    put("tools", toolsArray)
                }.toString()

                val response = httpClient.post(ClaudeExtractor.ANTHROPIC_MESSAGES_URL) {
                    contentType(ContentType.Application.Json)
                    header("x-api-key", apiKey)
                    header("anthropic-version", ClaudeExtractor.ANTHROPIC_VERSION)
                    setBody(requestPayload)
                }

                val statusCode = response.status.value
                val responseBody = response.bodyAsText()

                if (statusCode !in 200..299) {
                    return Result.failure(RuntimeException("Anthropic web search error: HTTP $statusCode $responseBody"))
                }

                val anthropicResponse = json.decodeFromString<AnthropicMessageResponse>(responseBody)
                finalResponse = anthropicResponse
                totalInputTokens += anthropicResponse.usage?.inputTokens ?: 0
                totalOutputTokens += anthropicResponse.usage?.outputTokens ?: 0
                totalCacheRead += anthropicResponse.usage?.cacheReadInputTokens ?: 0

                // Collect URLs from web_search_tool_result content blocks
                for (block in anthropicResponse.content) {
                    extractUrlsFromJsonElement(block.content, searchResultUrls)
                }

                if (anthropicResponse.stopReason == "pause_turn") {
                    // Send back assistant turn to continue
                    val assistantTurnContent = buildJsonArray {
                        for (block in anthropicResponse.content) {
                            add(buildJsonObject {
                                put("type", block.type)
                                block.text?.let { put("text", it) }
                                block.id?.let { put("id", it) }
                                block.name?.let { put("name", it) }
                                block.input?.let { put("input", it) }
                                block.toolUseId?.let { put("tool_use_id", it) }
                                block.content?.let { put("content", it) }
                            })
                        }
                    }
                    messages.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", assistantTurnContent)
                    })
                } else {
                    break
                }
            }

            val textBlock = finalResponse?.content?.lastOrNull { it.type == "text" }?.text ?: ""
            val parsedPicks = parseAndValidateWebPicks(textBlock, searchResultUrls)

            val usage = LlmUsage(
                inputTokens = totalInputTokens,
                outputTokens = totalOutputTokens,
                cacheReadTokens = totalCacheRead,
                webSearches = maxWebSearches,
                model = modelId
            )

            Result.success(parsedPicks to usage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun callProviderWebSearch(
        apiKey: String,
        modelId: String,
        userQuery: String,
        maxWebSearches: Int,
        provider: WebSearchProvider
    ): Result<Pair<List<WebRecipeSuggestion>, LlmUsage>> {
        return try {
            val searchResults = provider.search(userQuery, maxWebSearches)
            if (searchResults.isEmpty()) {
                return Result.success(emptyList<WebRecipeSuggestion>() to LlmUsage(0, 0, 0, 0, modelId))
            }

            val validUrls = searchResults.map { it.url }.toSet()
            val searchResultsText = buildString {
                appendLine("Search results for '$userQuery':")
                searchResults.forEachIndexed { index, res ->
                    appendLine("${index + 1}. Title: ${res.title}")
                    appendLine("   URL: ${res.url}")
                    appendLine("   Snippet: ${res.snippet}")
                    appendLine("   Site: ${res.site}")
                }
            }

            val prompt = """The user wants candidate recipes for: "$userQuery".
From the following search results, choose up to 3 best candidate recipes with full recipes.
URLs MUST exactly match the URLs in the search results.

$searchResultsText

Output strictly a JSON array in a ```json ... ``` code fence."""

            val requestPayload = buildJsonObject {
                put("model", modelId)
                put("max_tokens", 1000)
                putJsonArray("system") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", WEB_SYSTEM_PROMPT)
                    })
                }
                putJsonArray("messages") {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
            }.toString()

            val response = httpClient.post(ClaudeExtractor.ANTHROPIC_MESSAGES_URL) {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", ClaudeExtractor.ANTHROPIC_VERSION)
                setBody(requestPayload)
            }

            val statusCode = response.status.value
            val responseBody = response.bodyAsText()

            if (statusCode !in 200..299) {
                return Result.failure(RuntimeException("Anthropic API error: HTTP $statusCode $responseBody"))
            }

            val anthropicResponse = json.decodeFromString<AnthropicMessageResponse>(responseBody)
            val textContent = anthropicResponse.content.firstOrNull { it.type == "text" }?.text ?: ""

            val parsedPicks = parseAndValidateWebPicks(textContent, validUrls)

            val usage = LlmUsage(
                inputTokens = anthropicResponse.usage?.inputTokens ?: 0,
                outputTokens = anthropicResponse.usage?.outputTokens ?: 0,
                cacheReadTokens = anthropicResponse.usage?.cacheReadInputTokens ?: 0,
                webSearches = searchResults.size,
                model = modelId
            )

            Result.success(parsedPicks to usage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun parseAndValidateWebPicks(
        text: String,
        allowedUrls: Set<String>
    ): List<WebRecipeSuggestion> {
        if (allowedUrls.isEmpty()) return emptyList()
        return try {
            val jsonText = extractJsonBlock(text)
            val rawList = json.decodeFromString<List<WebRecipeSuggestion>>(jsonText)

            fun normalize(u: String) = u.trim().removeSuffix("/").lowercase()
            val normalizedAllowed = allowedUrls.map { normalize(it) }.toSet()

            // Strictly filter URLs: keep ONLY URLs present in allowedUrls
            val filtered = rawList.filter { suggestion ->
                val norm = normalize(suggestion.url)
                normalizedAllowed.contains(norm) || normalizedAllowed.any { norm.startsWith(it) || it.startsWith(norm) }
            }.map { suggestion ->
                val site = suggestion.site.ifBlank {
                    try { URI(suggestion.url).host?.removePrefix("www.") ?: "" } catch (e: Exception) { "" }
                }
                suggestion.copy(site = site)
            }

            filtered.take(MAX_WEB_PICKS)
        } catch (e: Exception) {
            emptyList()
        }
    }

    internal fun extractJsonBlock(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("```json")) {
            val content = trimmed.substringAfter("```json").substringBefore("```").trim()
            if (content.isNotEmpty()) return content
        }
        if (trimmed.startsWith("```")) {
            val content = trimmed.substringAfter("```").substringBefore("```").trim()
            if (content.isNotEmpty()) return content
        }

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

    private fun extractUrlsFromJsonElement(element: JsonElement?, urls: MutableSet<String>) {
        if (element == null) return
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    if (key.equals("url", ignoreCase = true) && value is JsonPrimitive && value.isString) {
                        urls.add(value.content)
                    } else {
                        extractUrlsFromJsonElement(value, urls)
                    }
                }
            }
            is JsonArray -> {
                for (item in element) {
                    extractUrlsFromJsonElement(item, urls)
                }
            }
            is JsonPrimitive -> {
                if (element.isString && (element.content.startsWith("http://") || element.content.startsWith("https://"))) {
                    urls.add(element.content)
                }
            }
        }
    }
}

@Serializable
private data class RawChatPlanResponse(
    val reply: String = "",
    val suggestions: List<RecipeSuggestion> = emptyList(),
    @SerialName("follow_up_question") val followUpQuestion: String = ""
)
