package org.eu.angsumanray.recipecatcher.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.eu.angsumanray.recipecatcher.core.model.ChatMessage
import org.eu.angsumanray.recipecatcher.core.ports.SearchResult
import org.eu.angsumanray.recipecatcher.core.ports.WebSearchProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPlannerTest {

    private fun createClient(handler: (request: HttpRequestData, body: String) -> Pair<HttpStatusCode, String>): HttpClient {
        return HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            engine {
                addHandler { request ->
                    val body = when (val content = request.body) {
                        is TextContent -> content.text
                        else -> ""
                    }
                    val (status, responseBody) = handler(request, body)
                    respond(
                        content = responseBody,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
    }

    private fun isWebCall(body: String) = body.contains("\"tools\"") || body.contains("Search results:")

    private fun textMessage(vararg texts: String, inputTokens: Int = 300, outputTokens: Int = 80): String =
        buildJsonObject {
            put("type", "message")
            put("role", "assistant")
            put("stop_reason", "end_turn")
            putJsonArray("content") {
                texts.forEach { t -> add(buildJsonObject { put("type", "text"); put("text", t) }) }
            }
            putJsonObject("usage") {
                put("input_tokens", inputTokens)
                put("output_tokens", outputTokens)
            }
        }.toString()

    private fun searchResultBlock(toolUseId: String, vararg urls: String) = buildJsonObject {
        put("type", "web_search_tool_result")
        put("tool_use_id", toolUseId)
        putJsonArray("content") {
            urls.forEach { u ->
                add(buildJsonObject {
                    put("type", "web_search_result")
                    put("url", u)
                    put("title", "Result")
                    put("encrypted_content", "enc-$toolUseId")
                })
            }
        }
    }

    private fun serverToolUseBlock(id: String) = buildJsonObject {
        put("type", "server_tool_use")
        put("id", id)
        put("name", "web_search")
        putJsonObject("input") { put("query", "paneer recipe") }
    }

    private fun webMessage(blocks: List<JsonObject>, stopReason: String = "end_turn", searches: Int = 1): String =
        buildJsonObject {
            put("type", "message")
            put("role", "assistant")
            put("stop_reason", stopReason)
            put("content", JsonArray(blocks))
            putJsonObject("usage") {
                put("input_tokens", 1000)
                put("output_tokens", 200)
                putJsonObject("server_tool_use") { put("web_search_requests", searches) }
            }
        }.toString()

    private fun textBlock(text: String) = buildJsonObject { put("type", "text"); put("text", text) }

    private val libraryJson = textMessage(
        """{"reply": "Here are ideas.", "suggestions": [{"recipe_id": 1, "reason": "Quick paneer", "missing": []}], "follow_up_question": ""}""",
        inputTokens = 400,
        outputTokens = 100
    )

    private val sampleLibrary = listOf(
        RecipeIndexEntry(
            id = 1L,
            title = "Palak Paneer",
            tags = listOf("indian", "curry", "vegetarian"),
            mainIngredients = listOf("paneer", "spinach", "onion", "garlic"),
            totalMinutes = 30,
            complexity = "easy",
            favourite = true
        ),
        RecipeIndexEntry(
            id = 2L,
            title = "Chicken Biryani",
            tags = listOf("indian", "rice"),
            mainIngredients = listOf("chicken", "rice", "spices"),
            totalMinutes = 60,
            complexity = "hard"
        ),
        RecipeIndexEntry(
            id = 3L,
            title = "Tomato Soup",
            tags = listOf("soup", "quick"),
            mainIngredients = listOf("tomatoes", "cream", "basil"),
            totalMinutes = 20,
            complexity = "easy"
        )
    )

    @Test
    fun testLibraryCallValidatesRecipeIdsAndCapsSuggestions() = runBlocking {
        val response = textMessage(
            """{"reply": "Palak Paneer is great for tonight.", "suggestions": [{"recipe_id": 1, "reason": "30 min, uses spinach", "missing": []}, {"recipe_id": 999, "reason": "Fake recipe", "missing": []}], "follow_up_question": "Do you have garlic?"}""",
            inputTokens = 500,
            outputTokens = 120
        )

        val client = createClient { _, _ -> HttpStatusCode.OK to response }
        val planner = ChatPlanner(httpClient = client, apiKeyProvider = { "test-api-key" })

        val result = planner.planChat(
            userMessage = "What can I cook with spinach?",
            libraryRecipes = sampleLibrary,
            includeWeb = false
        )

        assertTrue(result.isSuccess)
        val plan = result.getOrThrow()
        assertEquals("Palak Paneer is great for tonight.", plan.reply)
        assertEquals("Do you have garlic?", plan.followUpQuestion)
        assertEquals(listOf(1L), plan.suggestions.map { it.recipeId })
        assertEquals(500, plan.usage.inputTokens)
        assertNull(plan.webUsage)
    }

    @Test
    fun testLibraryCallDropsDuplicatesAndCapsAtFive() = runBlocking {
        val library = (1L..8L).map { RecipeIndexEntry(id = it, title = "Dish $it") }
        val suggestions = (listOf(1L, 1L) + (2L..8L)).joinToString(",") { """{"recipe_id": $it}""" }
        val response = textMessage("""Sure! ```json
{"reply": "Lots of options.", "suggestions": [$suggestions]}
```""")
        val planner = ChatPlanner(createClient { _, _ -> HttpStatusCode.OK to response }, { "key" })

        val plan = planner.planChat("dinner ideas", libraryRecipes = library).getOrThrow()

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), plan.suggestions.map { it.recipeId })
    }

    @Test
    fun testBareFollowUpBecomesReply() = runBlocking {
        val response = textMessage("""{"reply": "", "suggestions": [], "follow_up_question": "Veg or non-veg?"}""")
        val planner = ChatPlanner(createClient { _, _ -> HttpStatusCode.OK to response }, { "key" })

        val plan = planner.planChat("food", libraryRecipes = sampleLibrary).getOrThrow()

        assertEquals("Veg or non-veg?", plan.reply)
        assertEquals("", plan.followUpQuestion)
    }

    @Test
    fun testUnauthorizedMapsToApiKeyException() = runBlocking {
        val planner = ChatPlanner(createClient { _, _ -> HttpStatusCode.Unauthorized to "{}" }, { "bad" })

        val result = planner.planChat("hi", libraryRecipes = sampleLibrary)

        assertTrue(result.exceptionOrNull() is ApiKeyInvalidException)
        assertEquals("Add or check your Anthropic API key in Settings.", llmErrorMessage(result.exceptionOrNull()))
    }

    @Test
    fun testHistoryExcludesDuplicateCurrentMessageAndLeadingAssistant() = runBlocking {
        var sentBody = ""
        val planner = ChatPlanner(
            createClient { _, body -> sentBody = body; HttpStatusCode.OK to libraryJson },
            { "key" }
        )
        val history = listOf(
            ChatMessage(role = "assistant", text = "Welcome back"),
            ChatMessage(role = "user", text = "I have paneer"),
            ChatMessage(role = "assistant", text = "Try palak paneer"),
            ChatMessage(role = "assistant", text = "   "),
            ChatMessage(role = "user", text = "Something spicier?")
        )

        planner.planChat("Something spicier?", history = history, libraryRecipes = sampleLibrary).getOrThrow()

        val messages = Json.parseToJsonElement(sentBody).jsonObject["messages"]!!.jsonArray
        val turns = messages.map { it.jsonObject["role"]!!.jsonPrimitive.content to it.jsonObject["content"]!!.jsonPrimitive.content }
        assertEquals(
            listOf("user" to "I have paneer", "assistant" to "Try palak paneer", "user" to "Something spicier?"),
            turns
        )
    }

    @Test
    fun testPrefilterRecipesAboveThreshold() {
        val planner = ChatPlanner(
            httpClient = HttpClient(MockEngine) { engine { addHandler { respond("") } } },
            apiKeyProvider = { "key" }
        )

        val recipes = (1..350).map { i ->
            RecipeIndexEntry(
                id = i.toLong(),
                title = if (i == 42) "Spicy Paneer Bhurji" else "Dish number $i",
                tags = if (i == 42) listOf("paneer", "spicy") else listOf("generic"),
                mainIngredients = if (i == 42) listOf("paneer", "green chili") else listOf("ingredient-$i")
            )
        }

        val filtered = planner.prefilterRecipes(recipes, "I want something with paneer", cap = 150)
        assertEquals(150, filtered.size)
        assertEquals(42L, filtered[0].id)
        assertEquals(recipes.take(300).map { it.id }, planner.prefilterRecipes(recipes.take(300), "paneer").map { it.id })
    }

    @Test
    fun testPrefilterUsesFtsHits() {
        val planner = ChatPlanner(HttpClient(MockEngine) { engine { addHandler { respond("") } } }, { "key" })
        val recipes = (1..400).map { RecipeIndexEntry(id = it.toLong(), title = "Dish $it") }

        // Only full-text search knows recipe 377 mentions jalapeño in its steps.
        val filtered = planner.prefilterRecipes(recipes, "jalapeño", ftsHits = mapOf(377L to 1))

        assertEquals(377L, filtered[0].id)
        assertEquals(ChatPlanner.PRE_FILTER_CAP, filtered.size)
    }

    @Test
    fun testSearchTermsDropStopWords() {
        assertEquals(listOf("eggs", "onions"), ChatPlanner.searchTerms("I have eggs, onions and 20 minutes"))
        assertEquals(listOf("jalapeño"), ChatPlanner.searchTerms("something with jalapeño"))
    }

    @Test
    fun testServerWebSearchKeepsOnlyResultUrlsAndCountsSearches() = runBlocking {
        var webBody = ""
        val webResponse = webMessage(
            listOf(
                serverToolUseBlock("srvtoolu_1"),
                searchResultBlock("srvtoolu_1", "https://example.com/paneer-tikka", "https://www.youtube.com/watch?v=1"),
                textBlock("Here are picks:\n```json\n[{\"title\": \"Paneer Tikka\", \"url\": \"https://EXAMPLE.com/paneer-tikka/\", "),
                textBlock("\"site\": \"\", \"reason\": \"Grilled\"}, {\"title\": \"Video\", \"url\": \"https://www.youtube.com/watch?v=1\"}, {\"title\": \"Made up\", \"url\": \"https://example.com/paneer-tikka-evil\"}]\n```")
            ),
            searches = 2
        )
        val client = createClient { _, body ->
            if (isWebCall(body)) {
                webBody = body
                HttpStatusCode.OK to webResponse
            } else {
                HttpStatusCode.OK to libraryJson
            }
        }
        val planner = ChatPlanner(httpClient = client, apiKeyProvider = { "key" })

        val plan = planner.planChat("paneer recipe", libraryRecipes = sampleLibrary, includeWeb = true, maxWebSearches = 2).getOrThrow()

        val request = Json.parseToJsonElement(webBody).jsonObject
        val tool = request["tools"]!!.jsonArray[0].jsonObject
        assertEquals(ChatPlanner.WEB_SEARCH_TOOL_TYPE, tool["type"]!!.jsonPrimitive.content)
        assertEquals("web_search", tool["name"]!!.jsonPrimitive.content)
        assertEquals(2, tool["max_uses"]!!.jsonPrimitive.int)
        assertEquals("auto", request["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(request.containsKey("output_config"))

        assertEquals(listOf("https://example.com/paneer-tikka"), plan.webSuggestions.map { it.url })
        assertEquals("example.com", plan.webSuggestions[0].site)
        assertEquals(1, plan.suggestions.size)
        assertEquals(400, plan.usage.inputTokens)
        assertEquals(0, plan.usage.webSearches)
        assertEquals(2, plan.webUsage!!.webSearches)
        assertEquals(1000, plan.webUsage!!.inputTokens)
        assertEquals("", plan.webNote)
    }

    @Test
    fun testPauseTurnIsSentBackAndContinued() = runBlocking {
        val bodies = mutableListOf<String>()
        val paused = webMessage(
            listOf(serverToolUseBlock("srvtoolu_1"), searchResultBlock("srvtoolu_1", "https://site.com/dal")),
            stopReason = "pause_turn"
        )
        val finished = webMessage(
            listOf(textBlock("```json\n[{\"title\": \"Dal\", \"url\": \"https://site.com/dal\", \"site\": \"site.com\", \"reason\": \"Classic\"}]\n```")),
            searches = 0
        )
        val client = createClient { _, body ->
            bodies.add(body)
            HttpStatusCode.OK to if (bodies.size == 1) paused else finished
        }
        val planner = ChatPlanner(httpClient = client, apiKeyProvider = { "key" })

        val picks = planner.searchWeb("dal", maxWebSearches = 3).getOrThrow()

        assertEquals(2, bodies.size)
        val secondMessages = Json.parseToJsonElement(bodies[1]).jsonObject["messages"]!!.jsonArray
        assertEquals(2, secondMessages.size)
        val assistantTurn = secondMessages[1].jsonObject
        assertEquals("assistant", assistantTurn["role"]!!.jsonPrimitive.content)
        assertTrue(assistantTurn.toString().contains("enc-srvtoolu_1"))
        assertEquals(listOf("https://site.com/dal"), picks.picks.map { it.url })
        assertEquals(1, picks.usage.webSearches)
        assertEquals(2000, picks.usage.inputTokens)
    }

    @Test
    fun testServerFailureFallsBackToSearchProviderWithoutBilledSearches() = runBlocking {
        val fallbackPick = textMessage(
            "```json\n[{\"title\": \"Paneer Tikka\", \"url\": \"https://example.com/paneer-tikka\", \"site\": \"example.com\", \"reason\": \"Grilled\"}, {\"title\": \"Hallucinated\", \"url\": \"https://hallucinated.com/fake\"}]\n```"
        )
        val client = createClient { _, body ->
            when {
                body.contains("\"tools\"") -> HttpStatusCode.BadRequest to """{"type":"error","error":{"type":"invalid_request_error","message":"web search disabled"}}"""
                body.contains("Search results:") -> HttpStatusCode.OK to fallbackPick
                else -> HttpStatusCode.OK to libraryJson
            }
        }
        val provider = object : WebSearchProvider {
            override suspend fun search(query: String, maxResults: Int) = listOf(
                SearchResult(title = "Paneer Tikka Recipe", url = "https://example.com/paneer-tikka", site = "example.com")
            )
        }
        val planner = ChatPlanner(httpClient = client, apiKeyProvider = { "key" }, fallbackSearchProvider = provider)

        val plan = planner.planChat("paneer recipe", libraryRecipes = sampleLibrary, includeWeb = true).getOrThrow()

        assertEquals(listOf("https://example.com/paneer-tikka"), plan.webSuggestions.map { it.url })
        assertEquals(0, plan.webUsage!!.webSearches)
    }

    @Test
    fun testWebFailureKeepsLibraryReplyAndAddsNote() = runBlocking {
        val client = createClient { _, body ->
            if (isWebCall(body)) HttpStatusCode.ServiceUnavailable to "{}" else HttpStatusCode.OK to libraryJson
        }
        val emptyProvider = object : WebSearchProvider {
            override suspend fun search(query: String, maxResults: Int) = emptyList<SearchResult>()
        }
        val planner = ChatPlanner(httpClient = client, apiKeyProvider = { "key" }, fallbackSearchProvider = emptyProvider)

        val result = planner.planChat("paneer", libraryRecipes = sampleLibrary, includeWeb = true)

        assertTrue(result.isSuccess)
        val plan = result.getOrThrow()
        assertEquals("Here are ideas.", plan.reply)
        assertTrue(plan.webSuggestions.isEmpty())
        assertTrue(plan.webNote.startsWith("Web search failed"))
    }

    @Test
    fun testMalformedWebAnswerGivesNoPicks() {
        val planner = ChatPlanner(HttpClient(MockEngine) { engine { addHandler { respond("") } } }, { "key" })
        val allowed = setOf("https://example.com/a")

        assertTrue(planner.parseAndValidateWebPicks("Sorry, I couldn't find anything.", allowed).isEmpty())
        assertTrue(planner.parseAndValidateWebPicks("```json\n[{\"title\": \"A\", \"url\": ", allowed).isEmpty())
        assertTrue(planner.parseAndValidateWebPicks("""[{"title": "A", "url": "https://example.com/a"}]""", emptySet()).isEmpty())
    }

    @Test
    fun testFindWebCandidatesSendsDishAndHints() = runBlocking {
        var body = ""
        val response = webMessage(
            listOf(
                searchResultBlock("srvtoolu_1", "https://bongcook.com/ilish"),
                textBlock("```json\n[{\"title\": \"Bengali Shorshe Ilish\", \"url\": \"https://bongcook.com/ilish\", \"site\": \"bongcook.com\", \"reason\": \"Traditional\"}]\n```")
            )
        )
        val client = createClient { _, b -> body = b; HttpStatusCode.OK to response }
        val planner = ChatPlanner(httpClient = client, apiKeyProvider = { "key" })

        val result = planner.findWebCandidates("Shorshe Ilish", "traditional mustard", maxWebSearches = 4).getOrThrow()

        assertTrue(body.contains("Shorshe Ilish (traditional mustard)"))
        assertEquals(4, Json.parseToJsonElement(body).jsonObject["tools"]!!.jsonArray[0].jsonObject["max_uses"]!!.jsonPrimitive.int)
        assertEquals(listOf("https://bongcook.com/ilish"), result.picks.map { it.url })
    }

    @Test
    fun testExtractJsonBlockFindsFenceAfterProse() {
        assertEquals("[1, 2]", AnthropicSupport.extractJsonBlock("Here you go [see below]:\n```json\n[1, 2]\n```\nEnjoy"))
        assertEquals("{\"a\": 1}", AnthropicSupport.extractJsonBlock("prefix {\"a\": 1} suffix"))
    }

    private fun toolUseResponse(id: String, name: String, input: String, text: String = "") = """
        {"id":"msg_$id","type":"message","role":"assistant","model":"claude-sonnet-5-5","stop_reason":"tool_use",
         "content":[{"type":"thinking","thinking":"Checking ingredients","signature":"sig_$id"},${if (text.isNotEmpty()) "{\"type\":\"text\",\"text\":\"$text\"}," else ""}{"type":"tool_use","id":"$id","name":"$name","input":$input}],
         "usage":{"input_tokens":100,"output_tokens":20}}
    """.trimIndent()

    @Test
    fun testEditToolsLookUpIngredientsThenProposeValidatedChanges() = runBlocking {
        val bodies = mutableListOf<String>()
        val responses = ArrayDeque(
            listOf(
                toolUseResponse("tu_1", ChatPlanner.TOOL_GET_INGREDIENTS, """{"recipe_ids":[1,2,99]}"""),
                toolUseResponse(
                    "tu_2",
                    ChatPlanner.TOOL_PROPOSE_CHANGES,
                    """{"summary":"Mark dairy recipes","edits":[
                        {"recipe_id":1,"add":[{"kind":"diet","name":"dairy"},{"kind":"allergens","name":" lactose "}],"diet_type":"Vegetarian","reason":"has paneer"},
                        {"recipe_id":2,"add":[{"kind":"tag","name":"rich"}],"remove":[{"kind":"tag","name":"rich"}]},
                        {"recipe_id":99,"add":[{"kind":"diet","name":"dairy"}]},
                        {"recipe_id":1,"add":[{"kind":"tag","name":"duplicate"}]}
                    ]}"""
                )
            )
        )
        var lookedUp: List<Long> = emptyList()
        val planner = ChatPlanner(createClient { _, body -> bodies += body; HttpStatusCode.OK to responses.removeFirst() }, { "key" })
        val tools = LibraryEditTools(tagNames = mapOf("diet" to listOf("dairy", "gluten"))) { ids ->
            lookedUp = ids
            mapOf(1L to listOf("paneer", "cream"), 2L to listOf("chicken", "rice"))
        }

        val result = planner.planChat("mark recipes with dairy", libraryRecipes = sampleLibrary, editTools = tools).getOrThrow()

        assertEquals(listOf(1L, 2L), lookedUp)
        val second = Json.parseToJsonElement(bodies[1]).jsonObject["messages"]!!.jsonArray
        val toolResult = second.last().jsonObject["content"]!!.jsonArray.first().jsonObject
        assertEquals("tu_1", toolResult["tool_use_id"]!!.jsonPrimitive.content)
        assertTrue(toolResult["content"]!!.jsonPrimitive.content.contains("1: paneer; cream"))
        // The assistant turn is echoed back verbatim, thinking signature included.
        val echoed = second[second.size - 2].toString()
        assertTrue(echoed.contains("\"signature\":\"sig_tu_1\""))
        assertFalse(echoed.contains("null"))
        assertTrue(Json.parseToJsonElement(bodies[0]).jsonObject["system"].toString().contains("diet: dairy, gluten"))

        val edits = result.edits!!
        assertEquals("Mark dairy recipes", edits.summary)
        assertEquals(listOf(1L), edits.edits.map { it.recipeId })
        assertEquals(listOf(TagRef("diet", "dairy"), TagRef("diet", "lactose")), edits.edits.single().add)
        assertEquals("vegetarian", edits.edits.single().dietType)
        assertEquals(LibraryEditProposal.STATUS_PROPOSED, edits.status)
        assertEquals(200, result.usage.inputTokens)
    }

    @Test
    fun testLibraryCallWithoutEditToolsSendsNoTools() = runBlocking {
        var sentBody = ""
        val planner = ChatPlanner(createClient { _, body -> sentBody = body; HttpStatusCode.OK to libraryJson }, { "key" })
        planner.planChat("dinner ideas", libraryRecipes = sampleLibrary).getOrThrow()
        assertNull(Json.parseToJsonElement(sentBody).jsonObject["tools"])
    }
}
