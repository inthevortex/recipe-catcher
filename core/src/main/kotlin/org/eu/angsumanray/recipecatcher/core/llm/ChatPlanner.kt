package org.eu.angsumanray.recipecatcher.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.eu.angsumanray.recipecatcher.core.model.ChatMessage
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

    companion object {
        const val HEADER = "id | title | tags | main ingredients | total min | complexity | spice | diet | contains | kcal | make-ahead / freezer | equipment | favourite | last cooked"
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
    /** Set when web picks were asked for but the web call failed; the library reply still stands. */
    val webNote: String = "",
    /** Library call usage. */
    val usage: LlmUsage = LlmUsage(0, 0, 0),
    val webUsage: LlmUsage? = null,
    /** Library changes the user asked for, shown for review before anything is written. */
    val edits: LibraryEditProposal? = null
)

/** Web picks plus the usage of the call that found them. */
data class WebPicks(
    val picks: List<WebRecipeSuggestion>,
    val usage: LlmUsage
)

class ChatPlanner(
    private val httpClient: HttpClient,
    private val apiKeyProvider: suspend () -> String,
    private val modelIdProvider: suspend () -> String = { ClaudeExtractor.DEFAULT_MODEL_ID },
    /** Used only when Claude's own web search tool call fails. */
    private val fallbackSearchProvider: WebSearchProvider? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {

    companion object {
        const val MAX_SUGGESTIONS = 5
        const val MAX_WEB_PICKS = 3
        const val HISTORY_LIMIT = 10
        const val PRE_FILTER_THRESHOLD = 300
        const val PRE_FILTER_CAP = 150
        const val WEB_SEARCH_TOOL_TYPE = "web_search_20260209"
        private const val MAX_PAUSE_CONTINUATIONS = 4
        private const val FALLBACK_RESULT_COUNT = 8
        private const val MAX_SEARCH_TERMS = 12
        private const val MAX_TOOL_ROUNDS = 10
        private const val MAX_INGREDIENT_LOOKUP = 100
        private const val SUGGEST_MAX_TOKENS = 1500
        /** A proposal touching most of a library needs room for a line per recipe. */
        private const val EDIT_MAX_TOKENS = 8000

        const val TOOL_FIND_RECIPES = "find_recipes"
        const val TOOL_GET_INGREDIENTS = "get_recipe_ingredients"
        const val TOOL_PROPOSE_CHANGES = "propose_library_changes"

        private val VIDEO_HOSTS = listOf("youtube.com", "youtu.be", "vimeo.com", "tiktok.com", "instagram.com")

        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "without", "have", "has", "had", "can", "could", "would", "should",
            "what", "which", "something", "some", "any", "want", "need", "make", "cook", "cooking", "tonight",
            "today", "tomorrow", "recipe", "recipes", "dish", "dishes", "meal", "meals", "please", "give", "show",
            "suggest", "idea", "ideas", "about", "that", "this", "from", "into", "only", "just", "like", "use",
            "using", "got", "are", "you", "your", "our", "but", "not", "too", "very", "more", "less", "minutes",
            "min", "mins", "hour", "hours", "quick", "easy", "how", "why", "when", "where", "who"
        )

        /** Words from a chat message worth matching against the library: no stop words, no duplicates. */
        fun searchTerms(message: String): List<String> = message.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
            .distinct()
            .take(MAX_SEARCH_TERMS)

        const val LIBRARY_SYSTEM_PROMPT = """You are a helpful culinary assistant for a personal offline recipe app.
Suggest at most 5 recipes, ONLY by recipe_id from the provided recipe index.
Weigh ingredients on hand, mood, time, spice preference, diet and "without" requests ("veg, no dairy"), lighter or heavier meals by calories, equipment the user has or lacks ("no oven today"), whether they want something to make ahead, favourites, and how long since each was cooked.
List missing ingredients for suggested recipes.
If the user's request is too vague, ask ONE concise follow-up question.
If the user asks a question about cooking techniques, ingredients, or variations, answer clearly and constructively in the reply.

Unless you are calling a tool, you MUST respond strictly with a valid JSON object adhering to this schema:
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

        const val LIBRARY_EDIT_PROMPT = """You can also change recipe data when the user asks, e.g. "tag every air fryer recipe as quick", "mark recipes with paneer as containing dairy", "set these to vegetarian".
- Editable: tags (kind "tag"), allergens / contains (kind "diet"), equipment (kind "equipment"), consumables (kind "consumable"), diet_type (vegan, vegetarian, eggetarian, non-veg), complexity (easy, medium, hard), spice_level (mild, medium, hot), make_ahead and freezer_friendly.
- The index above may not list every recipe. Find recipes to change with find_recipes, which searches the whole library by ingredients, text, tags, diet, complexity, spice and time, and pages through long results.
- find_recipes matches words inside ingredient names, so check what matched ("coconut milk" is not dairy). When a decision needs a recipe's full ingredient list, call get_recipe_ingredients.
- Reuse an existing tag name when one fits; a new name creates a new tag.
- Then call propose_library_changes once. Group recipes that get the same change into one entry with all their recipe_ids. Leave out recipes that already have the change.
- The user reviews the proposal and applies it; never say the changes are already made.
For any other request, answer with the JSON above."""

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

        private const val DEFAULT_REPLY = "Here are some ideas from your library."
    }

    /**
     * Runs the library call and, when [includeWeb] is set, the web call in parallel.
     * A failed web call leaves the library reply intact and sets [ChatPlanResult.webNote].
     */
    suspend fun planChat(
        userMessage: String,
        history: List<ChatMessage> = emptyList(),
        libraryRecipes: List<RecipeIndexEntry>,
        includeWeb: Boolean = false,
        maxWebSearches: Int = 3,
        ftsHits: Map<Long, Int> = emptyMap(),
        today: String? = null,
        editTools: LibraryEditTools? = null
    ): Result<ChatPlanResult> = coroutineScope {
        val web = if (includeWeb) async { searchWeb(userMessage, maxWebSearches) } else null
        val library = planLibrary(userMessage, history, libraryRecipes, ftsHits, today, editTools)
        if (library.isFailure) {
            web?.cancel()
            return@coroutineScope library
        }
        val plan = library.getOrThrow()
        Result.success(if (web == null) plan else plan.withWeb(web.await()))
    }

    /**
     * The library call: suggestions validated against the index sent, capped at [MAX_SUGGESTIONS].
     * With [editTools], Claude may also read ingredients and propose library changes.
     */
    suspend fun planLibrary(
        userMessage: String,
        history: List<ChatMessage> = emptyList(),
        libraryRecipes: List<RecipeIndexEntry>,
        ftsHits: Map<Long, Int> = emptyMap(),
        today: String? = null,
        editTools: LibraryEditTools? = null
    ): Result<ChatPlanResult> {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return Result.failure(ApiKeyInvalidException("API key is empty"))
        }
        val modelId = modelIdProvider().trim().ifEmpty { ClaudeExtractor.DEFAULT_MODEL_ID }

        val turns = conversationTurns(history, userMessage)
        // A short follow-up like "something spicier" borrows terms from the previous request.
        val filterQuery = userMessage.takeIf { searchTerms(it).isNotEmpty() }
            ?: turns.dropLast(1).lastOrNull { it.first == "user" }?.second
            ?: userMessage
        val recipes = prefilterRecipes(libraryRecipes, filterQuery, ftsHits)
        return callLibraryLlm(apiKey, modelId, turns, recipes, today, editTools)
    }

    /** The web call on its own, for chat. */
    suspend fun searchWeb(userMessage: String, maxWebSearches: Int = 3): Result<WebPicks> {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return Result.failure(ApiKeyInvalidException("API key is empty"))
        }
        val modelId = modelIdProvider().trim().ifEmpty { ClaudeExtractor.DEFAULT_MODEL_ID }
        return callWebSearch(apiKey, modelId, userMessage, maxWebSearches.coerceIn(1, 10))
    }

    /** The web call for Find with AI: the dish name and hints are the whole request. */
    suspend fun findWebCandidates(
        dishName: String,
        hints: String = "",
        maxWebSearches: Int = 3
    ): Result<WebPicks> {
        val query = if (hints.isNotBlank()) "${dishName.trim()} (${hints.trim()})" else dishName.trim()
        return searchWeb(query, maxWebSearches)
    }

    private fun ChatPlanResult.withWeb(web: Result<WebPicks>): ChatPlanResult = web.fold(
        onSuccess = { copy(webSuggestions = it.picks, webUsage = it.usage) },
        onFailure = { copy(webNote = "Web search failed: ${llmErrorMessage(it)}") }
    )

    /**
     * Above [PRE_FILTER_THRESHOLD] recipes, keeps the [cap] best matches for [query]. [ftsHits] counts
     * how many of the query's terms matched each recipe in full-text search; title, ingredient and tag
     * matches add to that. Ties keep the input order.
     */
    internal fun prefilterRecipes(
        recipes: List<RecipeIndexEntry>,
        query: String,
        ftsHits: Map<Long, Int> = emptyMap(),
        cap: Int = PRE_FILTER_CAP
    ): List<RecipeIndexEntry> {
        if (recipes.size <= PRE_FILTER_THRESHOLD) return recipes

        val queryWords = searchTerms(query).toSet()
        if (queryWords.isEmpty() && ftsHits.isEmpty()) return recipes.take(cap)

        val wordSplit = Regex("[^\\p{L}\\p{N}]+")
        fun words(values: List<String>) = values.flatMap { it.lowercase().split(wordSplit) }.toSet()

        fun score(recipe: RecipeIndexEntry): Int {
            var score = (ftsHits[recipe.id] ?: 0) * 4
            val titleWords = words(listOf(recipe.title))
            val ingWords = words(recipe.mainIngredients)
            val tagWords = words(recipe.tags + recipe.contains + listOfNotNull(recipe.dietType))
            for (w in queryWords) {
                if (w in titleWords) score += 5
                if (w in ingWords) score += 3
                if (w in tagWords) score += 2
            }
            if (recipe.favourite) score += 1
            return score
        }

        return recipes.sortedByDescending { score(it) }.take(cap)
    }

    /**
     * The last [HISTORY_LIMIT] turns plus the new message, as (role, text) pairs. Blank turns and
     * leading assistant turns are dropped, and a trailing copy of the new message is not sent twice.
     */
    internal fun conversationTurns(history: List<ChatMessage>, userMessage: String): List<Pair<String, String>> {
        val turns = history
            .filter { it.text.isNotBlank() }
            .map { (if (it.role.equals("user", ignoreCase = true)) "user" else "assistant") to it.text.trim() }
            .toMutableList()
        if (turns.lastOrNull() == ("user" to userMessage.trim())) turns.removeAt(turns.lastIndex)
        val recent = turns.takeLast(HISTORY_LIMIT).dropWhile { it.first == "assistant" }
        return recent + ("user" to userMessage.trim())
    }

    private suspend fun callLibraryLlm(
        apiKey: String,
        modelId: String,
        turns: List<Pair<String, String>>,
        recipes: List<RecipeIndexEntry>,
        today: String?,
        editTools: LibraryEditTools?
    ): Result<ChatPlanResult> {
        val validIds = recipes.map { it.id }.toSet()
        val systemText = buildString {
            append(LIBRARY_SYSTEM_PROMPT)
            if (editTools != null) {
                append("\n\n")
                append(LIBRARY_EDIT_PROMPT)
            }
            if (today != null) append("\n\nToday is $today.")
            if (editTools != null && editTools.tagNames.isNotEmpty()) {
                append("\n\nExisting tags by kind:\n")
                editTools.tagNames.forEach { (kind, names) -> appendLine("$kind: ${names.joinToString(", ")}") }
            }
            append("\n\nRecipe Index (${recipes.size} recipes):\n")
            appendLine(RecipeIndexEntry.HEADER)
            recipes.forEach { appendLine(it.toIndexLine()) }
        }

        val messages = turns.map { (role, text) ->
            buildJsonObject {
                put("role", role)
                put("content", text)
            }
        }.toMutableList<JsonElement>()

        var inputTokens = 0
        var outputTokens = 0
        var cacheReadTokens = 0
        var responseModel: String? = null
        fun usage() = LlmUsage(inputTokens, outputTokens, cacheReadTokens, model = responseModel ?: modelId)

        return try {
            repeat(MAX_TOOL_ROUNDS) {
                val requestPayload = buildJsonObject {
                    put("model", modelId)
                    put("max_tokens", if (editTools != null) EDIT_MAX_TOKENS else SUGGEST_MAX_TOKENS)
                    if (editTools != null) put("tools", editToolDefinitions())
                    putJsonArray("system") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", systemText)
                            // The index repeats on every message of a conversation.
                            putJsonObject("cache_control") { put("type", "ephemeral") }
                        })
                    }
                    put("messages", JsonArray(messages))
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
                    return Result.failure(AnthropicSupport.httpError(statusCode, responseBody, modelId))
                }

                val anthropicResponse = json.decodeFromString<AnthropicMessageResponse>(responseBody)
                inputTokens += anthropicResponse.usage?.inputTokens ?: 0
                outputTokens += anthropicResponse.usage?.outputTokens ?: 0
                cacheReadTokens += anthropicResponse.usage?.cacheReadInputTokens ?: 0
                responseModel = anthropicResponse.model ?: responseModel

                val toolUses = anthropicResponse.content.filter { it.type == "tool_use" }
                if (editTools == null || toolUses.isEmpty()) {
                    return parseLibraryReply(AnthropicSupport.responseText(anthropicResponse), validIds, usage())
                }

                toolUses.firstOrNull { it.name == TOOL_PROPOSE_CHANGES }?.let { proposeCall ->
                    val raw = json.decodeFromJsonElement(RawEditProposal.serializer(), proposeCall.input ?: JsonObject(emptyMap()))
                    val libraryIds = editTools.library().map { it.id }.toSet()
                    val proposal = LibraryEditProposal.validated(raw.summary, raw.toEdits(), libraryIds)
                    val text = AnthropicSupport.responseText(anthropicResponse).trim()
                    val reply = when {
                        proposal.edits.isEmpty() -> text.ifEmpty { "No recipes need that change." }
                        else -> text.ifEmpty { proposal.summary.ifEmpty { "Here are the changes I'd make." } }
                    }
                    return Result.success(
                        ChatPlanResult(
                            reply = reply,
                            edits = proposal.takeIf { it.edits.isNotEmpty() },
                            usage = usage()
                        )
                    )
                }

                // Echoed back verbatim: thinking blocks must keep every field, signature included.
                val rawContent = json.parseToJsonElement(responseBody).jsonObject["content"] ?: JsonArray(emptyList())
                messages += buildJsonObject {
                    put("role", "assistant")
                    put("content", rawContent)
                }
                val results = toolUses.map { runEditTool(it, editTools) }
                messages += buildJsonObject {
                    put("role", "user")
                    put("content", JsonArray(results))
                }
            }
            Result.failure(RuntimeException("Claude didn't finish after $MAX_TOOL_ROUNDS tool calls"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseLibraryReply(textContent: String, validIds: Set<Long>, usage: LlmUsage): Result<ChatPlanResult> {
        if (textContent.isBlank()) {
            return Result.failure(RuntimeException("No text content returned from Claude"))
        }
        // A plain-prose answer (no JSON at all) is still a usable reply.
        if ('{' !in textContent) {
            return Result.success(ChatPlanResult(reply = textContent.trim(), usage = usage))
        }

        val rawResult = json.decodeFromString<RawChatPlanResponse>(AnthropicSupport.extractJsonBlock(textContent))
        val validatedSuggestions = rawResult.suggestions
            .filter { it.recipeId in validIds }
            .distinctBy { it.recipeId }
            .take(MAX_SUGGESTIONS)
            .map { s -> s.copy(reason = s.reason.trim(), missing = s.missing.map { it.trim() }.filter { it.isNotEmpty() }) }
        var followUp = rawResult.followUpQuestion.trim()
        val reply = rawResult.reply.trim().ifEmpty {
            // A bare follow-up question becomes the reply rather than an empty bubble.
            followUp.ifEmpty { DEFAULT_REPLY }.also { if (it == followUp) followUp = "" }
        }

        return Result.success(
            ChatPlanResult(
                reply = reply,
                suggestions = validatedSuggestions,
                followUpQuestion = followUp,
                usage = usage
            )
        )
    }

    /** Answers one read-only tool call with a `tool_result` block. */
    private suspend fun runEditTool(call: AnthropicContentBlock, editTools: LibraryEditTools): JsonObject {
        val input = call.input ?: JsonObject(emptyMap())
        val (text, isError) = try {
            when (call.name) {
                TOOL_FIND_RECIPES -> {
                    val query = json.decodeFromJsonElement(RecipeQuery.serializer(), input)
                    LibraryQueryEngine.describe(LibraryQueryEngine.find(query, editTools.library()), query.offset) to false
                }
                TOOL_GET_INGREDIENTS -> {
                    val byId = editTools.library().associateBy { it.id }
                    val ids = json.decodeFromJsonElement(RawIngredientLookup.serializer(), input).recipeIds
                        .filter { it in byId }.distinct().take(MAX_INGREDIENT_LOOKUP)
                    if (ids.isEmpty()) {
                        "No known recipe_ids given." to true
                    } else {
                        ids.joinToString("\n") { id ->
                            val recipe = byId.getValue(id)
                            "$id | ${recipe.title}: " + recipe.ingredients.joinToString("; ").ifEmpty { "no ingredients stored" }
                        } to false
                    }
                }
                else -> "Unknown tool ${call.name}" to true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Invalid input for ${call.name}: ${e.message}" to true
        }
        return buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", call.id.orEmpty())
            put("content", text)
            if (isError) put("is_error", true)
        }
    }

    private fun editToolDefinitions(): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("name", TOOL_FIND_RECIPES)
            put(
                "description",
                "Search the whole recipe library. All given criteria must hold. Returns matching ids, titles, diet type and the ingredients that matched, ${LibraryQueryEngine.PAGE_SIZE} per page."
            )
            putJsonObject("input_schema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("ingredients_any") {
                        stringArray("At least one ingredient name contains one of these words, e.g. [\"paneer\", \"cream\", \"ghee\"].")
                    }
                    putJsonObject("ingredients_none") { stringArray("No ingredient name contains any of these words.") }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Title, ingredients or tags contain this text.")
                    }
                    putJsonObject("has_tags") { tagRefArray() }
                    putJsonObject("lacks_tags") { tagRefArray() }
                    putJsonObject("diet_types") { stringArray("Diet type is one of these; \"unknown\" matches recipes without one.") }
                    putJsonObject("complexity") { stringEnum(ClaudeExtractor.COMPLEXITY_VALUES) }
                    putJsonObject("spice_level") { stringEnum(ClaudeExtractor.SPICE_LEVEL_VALUES) }
                    putJsonObject("max_total_minutes") { put("type", "integer") }
                    putJsonObject("offset") {
                        put("type", "integer")
                        put("description", "Skip this many matches, to page through long results.")
                    }
                }
            }
        })
        add(buildJsonObject {
            put("name", TOOL_GET_INGREDIENTS)
            put("description", "Full ingredient lists of recipes, for deciding allergens, diet or ingredient-based tags. Up to $MAX_INGREDIENT_LOOKUP ids per call.")
            putJsonObject("input_schema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("recipe_ids") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "integer") }
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("recipe_ids")) }
            }
        })
        add(buildJsonObject {
            put("name", TOOL_PROPOSE_CHANGES)
            put("description", "Propose changes to recipes for the user to review and apply. Call once; group recipes that get the same change.")
            putJsonObject("input_schema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("summary") {
                        put("type", "string")
                        put("description", "One sentence describing the change, e.g. 'Tag 12 air fryer recipes as quick'.")
                    }
                    putJsonObject("changes") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("recipe_ids") {
                                    put("type", "array")
                                    putJsonObject("items") { put("type", "integer") }
                                }
                                putJsonObject("add") { tagRefArray() }
                                putJsonObject("remove") { tagRefArray() }
                                putJsonObject("diet_type") { stringEnum(ClaudeExtractor.DIET_TYPE_VALUES) }
                                putJsonObject("complexity") { stringEnum(ClaudeExtractor.COMPLEXITY_VALUES) }
                                putJsonObject("spice_level") { stringEnum(ClaudeExtractor.SPICE_LEVEL_VALUES) }
                                putJsonObject("make_ahead") { put("type", "boolean") }
                                putJsonObject("freezer_friendly") { put("type", "boolean") }
                                putJsonObject("reason") {
                                    put("type", "string")
                                    put("description", "Why these recipes get this change, e.g. 'contain paneer or ghee'.")
                                }
                            }
                            putJsonArray("required") { add(JsonPrimitive("recipe_ids")) }
                        }
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("summary"))
                    add(JsonPrimitive("changes"))
                }
            }
        })
    }

    private fun JsonObjectBuilder.tagRefArray() {
        put("type", "array")
        putJsonObject("items") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("kind") { stringEnum(LibraryEditProposal.TAG_KINDS.toList()) }
                putJsonObject("name") { put("type", "string") }
            }
            putJsonArray("required") {
                add(JsonPrimitive("kind"))
                add(JsonPrimitive("name"))
            }
        }
    }

    private fun JsonObjectBuilder.stringArray(description: String) {
        put("type", "array")
        putJsonObject("items") { put("type", "string") }
        put("description", description)
    }

    private fun JsonObjectBuilder.stringEnum(values: List<String>) {
        put("type", "string")
        putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
    }

    private suspend fun callWebSearch(
        apiKey: String,
        modelId: String,
        userQuery: String,
        maxWebSearches: Int
    ): Result<WebPicks> {
        val serverResult = callServerWebSearch(apiKey, modelId, userQuery, maxWebSearches)
        if (serverResult.isSuccess || fallbackSearchProvider == null) return serverResult

        val error = serverResult.exceptionOrNull()
        // A bad key or model fails the fallback's Claude call the same way.
        if (error is ApiKeyInvalidException || error is ModelNotFoundException) return serverResult
        val fallback = callProviderWebSearch(apiKey, modelId, userQuery, fallbackSearchProvider)
        return if (fallback.isSuccess) fallback else serverResult
    }

    /**
     * Claude's server-side web_search tool. Search runs inside the request; a `pause_turn` stop is sent
     * back as the assistant turn so the model can finish. Only URLs returned in `web_search_tool_result`
     * blocks are kept.
     */
    private suspend fun callServerWebSearch(
        apiKey: String,
        modelId: String,
        userQuery: String,
        maxWebSearches: Int
    ): Result<WebPicks> {
        return try {
            val messages = mutableListOf<JsonElement>(
                buildJsonObject {
                    put("role", "user")
                    put("content", "Find candidate recipes for: $userQuery. Return up to 3 pages with full recipes, not videos.")
                }
            )
            val tools = buildJsonArray {
                add(buildJsonObject {
                    put("type", WEB_SEARCH_TOOL_TYPE)
                    put("name", "web_search")
                    put("max_uses", maxWebSearches)
                })
            }

            var inputTokens = 0
            var outputTokens = 0
            var cacheRead = 0
            var searches = 0
            val text = StringBuilder()
            val searchResultUrls = mutableSetOf<String>()

            for (attempt in 0..MAX_PAUSE_CONTINUATIONS) {
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
                    put("tools", tools)
                    putJsonObject("tool_choice") { put("type", "auto") }
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
                    return Result.failure(AnthropicSupport.httpError(statusCode, responseBody, modelId))
                }

                // Kept raw so a paused turn goes back with every field intact (citations, encrypted content).
                val raw = json.parseToJsonElement(responseBody).jsonObject
                val anthropicResponse = json.decodeFromJsonElement(AnthropicMessageResponse.serializer(), raw)
                inputTokens += anthropicResponse.usage?.inputTokens ?: 0
                outputTokens += anthropicResponse.usage?.outputTokens ?: 0
                cacheRead += anthropicResponse.usage?.cacheReadInputTokens ?: 0
                searches += anthropicResponse.usage?.serverToolUse?.webSearchRequests
                    ?: anthropicResponse.content.count { it.type == "server_tool_use" && it.name == "web_search" }

                anthropicResponse.content
                    .filter { it.type == "web_search_tool_result" }
                    .forEach { collectResultUrls(it.content, searchResultUrls) }
                text.append(AnthropicSupport.responseText(anthropicResponse))

                if (anthropicResponse.stopReason != "pause_turn") break
                messages.add(buildJsonObject {
                    put("role", "assistant")
                    put("content", raw["content"] ?: JsonArray(emptyList()))
                })
            }

            val usage = LlmUsage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cacheReadTokens = cacheRead,
                webSearches = searches,
                model = modelId
            )
            Result.success(WebPicks(parseAndValidateWebPicks(text.toString(), searchResultUrls), usage))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Fallback: a third-party search, then one plain Claude call to pick from its results. */
    private suspend fun callProviderWebSearch(
        apiKey: String,
        modelId: String,
        userQuery: String,
        provider: WebSearchProvider
    ): Result<WebPicks> {
        return try {
            val searchResults = provider.search(userQuery, FALLBACK_RESULT_COUNT)
            if (searchResults.isEmpty()) {
                return Result.failure(RuntimeException("Fallback web search returned no results"))
            }

            val prompt = buildString {
                appendLine("The user wants candidate recipes for: \"$userQuery\".")
                appendLine("From the following search results, choose up to 3 best candidate recipes with full recipes.")
                appendLine("URLs MUST exactly match the URLs in the search results.")
                appendLine()
                appendLine("Search results:")
                searchResults.forEachIndexed { index, res ->
                    appendLine("${index + 1}. Title: ${res.title}")
                    appendLine("   URL: ${res.url}")
                    appendLine("   Snippet: ${res.snippet}")
                    appendLine("   Site: ${res.site}")
                }
                appendLine()
                append("Output strictly a JSON array in a ```json ... ``` code fence.")
            }

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
                return Result.failure(AnthropicSupport.httpError(statusCode, responseBody, modelId))
            }

            val anthropicResponse = json.decodeFromString<AnthropicMessageResponse>(responseBody)
            val picks = parseAndValidateWebPicks(
                AnthropicSupport.responseText(anthropicResponse),
                searchResults.map { it.url }.toSet()
            )
            // No Anthropic searches ran, so none are billed.
            val usage = LlmUsage(
                inputTokens = anthropicResponse.usage?.inputTokens ?: 0,
                outputTokens = anthropicResponse.usage?.outputTokens ?: 0,
                cacheReadTokens = anthropicResponse.usage?.cacheReadInputTokens ?: 0,
                webSearches = 0,
                model = anthropicResponse.model ?: modelId
            )
            Result.success(WebPicks(picks, usage))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parses the fenced JSON picks and keeps only those whose URL matches one in [allowedUrls]
     * (ignoring scheme/host case, a trailing slash and any fragment). The search result's own URL is
     * kept, video hosts are dropped and duplicates collapse.
     */
    internal fun parseAndValidateWebPicks(
        text: String,
        allowedUrls: Set<String>
    ): List<WebRecipeSuggestion> {
        if (allowedUrls.isEmpty() || text.isBlank()) return emptyList()
        val rawList = try {
            json.decodeFromString<List<WebRecipeSuggestion>>(AnthropicSupport.extractJsonBlock(text))
        } catch (e: Exception) {
            return emptyList()
        }

        val allowedByKey = allowedUrls.associateBy { urlKey(it) }
        return rawList
            .mapNotNull { pick ->
                val url = allowedByKey[urlKey(pick.url)] ?: return@mapNotNull null
                val host = hostOf(url)
                if (host == null || VIDEO_HOSTS.any { host == it || host.endsWith(".$it") }) return@mapNotNull null
                pick.copy(
                    url = url,
                    title = pick.title.trim().ifEmpty { host },
                    site = pick.site.trim().ifEmpty { host.removePrefix("www.") }
                )
            }
            .distinctBy { urlKey(it.url) }
            .take(MAX_WEB_PICKS)
    }

    private fun hostOf(url: String): String? = try {
        URI(url.trim()).host?.lowercase()
    } catch (e: Exception) {
        null
    }

    private fun urlKey(url: String): String {
        val trimmed = url.trim().substringBefore('#')
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return trimmed.removeSuffix("/")
        val pathStart = trimmed.indexOf('/', schemeEnd + 3).let { if (it < 0) trimmed.length else it }
        val authority = trimmed.substring(0, pathStart).lowercase()
        return (authority + trimmed.substring(pathStart)).removeSuffix("/")
    }

    private fun collectResultUrls(element: JsonElement?, urls: MutableSet<String>) {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                if (key == "url" && value is JsonPrimitive && value.isString) {
                    urls.add(value.content)
                } else {
                    collectResultUrls(value, urls)
                }
            }
            is JsonArray -> element.forEach { collectResultUrls(it, urls) }
            else -> Unit
        }
    }
}

/**
 * What the edit tools need from the app: tag names to reuse, and the whole library, loaded once
 * on first use so the lookups run over every recipe rather than the index sent with the message.
 */
class LibraryEditTools(
    /** Existing tag names by kind ("tag", "diet", "equipment", "consumable"). */
    val tagNames: Map<String, List<String>>,
    private val loadLibrary: suspend () -> List<LibraryRecipe>
) {
    private var cached: List<LibraryRecipe>? = null

    suspend fun library(): List<LibraryRecipe> = cached ?: loadLibrary().also { cached = it }
}

@Serializable
private data class RawEditProposal(
    val summary: String = "",
    val changes: List<RawChangeGroup> = emptyList()
) {
    /** One edit per recipe; a recipe named in several groups gets all of their changes. */
    fun toEdits(): List<RecipeEdit> {
        val byRecipe = linkedMapOf<Long, RecipeEdit>()
        for (group in changes) {
            for (id in group.recipeIds.distinct()) {
                val current = byRecipe[id] ?: RecipeEdit(recipeId = id)
                byRecipe[id] = current.copy(
                    add = current.add + group.add,
                    remove = current.remove + group.remove,
                    dietType = group.dietType ?: current.dietType,
                    complexity = group.complexity ?: current.complexity,
                    spiceLevel = group.spiceLevel ?: current.spiceLevel,
                    makeAhead = group.makeAhead ?: current.makeAhead,
                    freezerFriendly = group.freezerFriendly ?: current.freezerFriendly,
                    reason = listOf(current.reason, group.reason.trim()).filter { it.isNotEmpty() }.distinct().joinToString("; ")
                )
            }
        }
        return byRecipe.values.toList()
    }
}

@Serializable
private data class RawChangeGroup(
    @SerialName("recipe_ids") val recipeIds: List<Long> = emptyList(),
    val add: List<TagRef> = emptyList(),
    val remove: List<TagRef> = emptyList(),
    @SerialName("diet_type") val dietType: String? = null,
    val complexity: String? = null,
    @SerialName("spice_level") val spiceLevel: String? = null,
    @SerialName("make_ahead") val makeAhead: Boolean? = null,
    @SerialName("freezer_friendly") val freezerFriendly: Boolean? = null,
    val reason: String = ""
)

@Serializable
private data class RawIngredientLookup(
    @SerialName("recipe_ids") val recipeIds: List<Long> = emptyList()
)

@Serializable
private data class RawChatPlanResponse(
    val reply: String = "",
    val suggestions: List<RecipeSuggestion> = emptyList(),
    @SerialName("follow_up_question") val followUpQuestion: String = ""
)
