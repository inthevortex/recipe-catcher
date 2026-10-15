package org.eu.angsumanray.recipecatcher.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.eu.angsumanray.recipecatcher.core.llm.ChatPlanResult
import org.eu.angsumanray.recipecatcher.core.llm.ChatPlanner
import org.eu.angsumanray.recipecatcher.core.ports.SearchResult
import org.eu.angsumanray.recipecatcher.core.ports.WebSearchProvider
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.settings.SecretStore
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRepositoryTest {

    private lateinit var database: RecipeDatabase
    private lateinit var secretStore: SecretStore
    private lateinit var settingsStore: SettingsStore
    private lateinit var recipeRepository: RecipeRepository
    private lateinit var chatRepository: ChatRepository
    private lateinit var mockEngine: MockEngine
    private val requestBodies = mutableListOf<String>()
    private var responder: (String) -> Pair<HttpStatusCode, String>? = { null }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        secretStore = SecretStore(context)
        settingsStore = SettingsStore(context, secretStore)
        recipeRepository = RecipeRepository(database)

        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        mockEngine = MockEngine { request ->
            val body = (request.body as? TextContent)?.text.orEmpty()
            requestBodies.add(body)
            responder(body)?.let { (status, content) ->
                return@MockEngine respond(content, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val responseJson = """
                {
                  "id": "msg_chat_123",
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    {
                      "type": "text",
                      "text": "{\n  \"reply\": \"You have a wonderful pasta recipe!\",\n  \"suggestions\": [\n    {\n      \"recipe_id\": 1,\n      \"reason\": \"Quick and easy\",\n      \"missing\": []\n    }\n  ],\n  \"follow_up_question\": \"\"\n}"
                    }
                  ],
                  "model": "claude-haiku-5-5",
                  "stop_reason": "end_turn",
                  "usage": {
                    "input_tokens": 120,
                    "output_tokens": 40,
                    "cache_read_input_tokens": 10
                  }
                }
            """.trimIndent()

            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        val dummySearchProvider = object : WebSearchProvider {
            override suspend fun search(query: String, maxResults: Int): List<SearchResult> = emptyList()
        }

        val chatPlanner = ChatPlanner(
            httpClient = httpClient,
            apiKeyProvider = { "sk-ant-dummy" },
            modelIdProvider = { "claude-haiku-5-5" },
            fallbackSearchProvider = dummySearchProvider
        )

        chatRepository = ChatRepository(
            context = context,
            database = database,
            chatPlanner = chatPlanner,
            settingsStore = settingsStore,
            recipeRepository = recipeRepository,
            recipeIndexBuilder = RecipeIndexBuilder(database),
            libraryEditor = LibraryEditor(database)
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun testGetOrCreateDefaultSession() = runBlocking {
        val sessionId1 = chatRepository.getOrCreateDefaultSession()
        assertTrue(sessionId1 > 0)

        val sessionId2 = chatRepository.getOrCreateDefaultSession()
        assertEquals(sessionId1, sessionId2)
    }

    @Test
    fun testSendMessageAndObserveMessages() = runBlocking {
        val sessionId = chatRepository.getOrCreateDefaultSession()

        // Insert a saved recipe so suggestion recipe_id=1 is valid
        database.recipeDao().insert(
            RecipeEntity(
                id = 1L,
                sourceType = "manual",
                sourceKey = "manual_1",
                title = "Garlic Butter Pasta",
                status = RecipeEntity.STATUS_SAVED
            )
        )

        val result = chatRepository.sendMessage(
            sessionId = sessionId,
            userText = "What can I cook tonight?",
            includeWeb = false
        )

        assertTrue(result.isSuccess)
        val assistantMessage = result.getOrThrow()
        assertEquals("You have a wonderful pasta recipe!", assistantMessage.text)
        assertNotNull(assistantMessage.suggestionsJson)

        // Observe messages
        val messages = chatRepository.observeMessages(sessionId).first()
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("What can I cook tonight?", messages[0].text)
        assertEquals("assistant", messages[1].role)
        assertEquals("You have a wonderful pasta recipe!", messages[1].text)

        // Verify LLM usage was logged
        val usages = database.llmUsageDao().getAll()
        assertEquals(1, usages.size)
        assertEquals("chat", usages[0].purpose)
        assertEquals(120, usages[0].inputTokens)
        assertEquals(40, usages[0].outputTokens)
    }

    @Test
    fun testClearMessagesForSession() = runBlocking {
        val sessionId = chatRepository.getOrCreateDefaultSession()
        chatRepository.sendMessage(sessionId, userText = "Hello", includeWeb = false)

        val beforeClear = chatRepository.observeMessages(sessionId).first()
        assertTrue(beforeClear.isNotEmpty())

        chatRepository.clearMessages(sessionId)

        val afterClear = chatRepository.observeMessages(sessionId).first()
        assertTrue(afterClear.isEmpty())
    }

    @Test
    fun testHistoryDoesNotRepeatTheNewMessage() = runBlocking {
        val sessionId = chatRepository.getOrCreateDefaultSession()
        chatRepository.sendMessage(sessionId, userText = "First question", includeWeb = false)
        chatRepository.sendMessage(sessionId, userText = "Second question", includeWeb = false)

        val messages = Json.parseToJsonElement(requestBodies.last()).jsonObject["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertTrue(messages.last().toString().contains("Second question"))
        assertEquals(1, messages.count { it.toString().contains("Second question") })
    }

    @Test
    fun testWebPicksAreAddedToReplyAndBilledAsWeb() = runBlocking {
        val webResponse = """
            {"type": "message", "role": "assistant", "stop_reason": "end_turn",
             "content": [
               {"type": "web_search_tool_result", "tool_use_id": "s1", "content": [{"type": "web_search_result", "url": "https://site.com/pasta", "title": "Pasta"}]},
               {"type": "text", "text": "```json\n[{\"title\": \"Pasta\", \"url\": \"https://site.com/pasta\", \"site\": \"site.com\", \"reason\": \"Quick\"}]\n```"}
             ],
             "usage": {"input_tokens": 900, "output_tokens": 60, "server_tool_use": {"web_search_requests": 2}}}
        """.trimIndent()
        responder = { body -> if (body.contains(ChatPlanner.WEB_SEARCH_TOOL_TYPE)) HttpStatusCode.OK to webResponse else null }
        val sessionId = chatRepository.getOrCreateDefaultSession()

        val result = chatRepository.sendMessage(sessionId, userText = "pasta please", includeWeb = true)

        assertTrue(result.isSuccess)
        val stored = chatRepository.observeMessages(sessionId).first().last()
        val plan = Json { ignoreUnknownKeys = true }.decodeFromString<ChatPlanResult>(stored.suggestionsJson!!)
        assertEquals(listOf("https://site.com/pasta"), plan.webSuggestions.map { it.url })
        val usages = database.llmUsageDao().getAll().associateBy { it.purpose }
        assertEquals(120, usages.getValue("chat").inputTokens)
        assertEquals(2, usages.getValue("web").webSearches)
        assertEquals(900, usages.getValue("web").inputTokens)
        assertEquals(0, usages.getValue("chat").webSearches)
    }

    @Test
    fun testLibraryFailureKeepsUserMessageAndReportsError() = runBlocking {
        responder = { HttpStatusCode.Unauthorized to "{}" }
        val sessionId = chatRepository.getOrCreateDefaultSession()

        val result = chatRepository.sendMessage(sessionId, userText = "Hello", includeWeb = false)

        assertTrue(result.isFailure)
        val messages = chatRepository.observeMessages(sessionId).first()
        assertEquals(listOf("user"), messages.map { it.role })
        assertTrue(database.llmUsageDao().getAll().isEmpty())
    }

    @Test
    fun testSessionTitleTakesFirstMessage() = runBlocking {
        val sessionId = chatRepository.getOrCreateDefaultSession()
        val longText = "x".repeat(80)

        chatRepository.sendMessage(sessionId, userText = longText, includeWeb = false)
        chatRepository.sendMessage(sessionId, userText = "later message", includeWeb = false)

        assertEquals("x".repeat(60), database.chatDao().getSession(sessionId)!!.title)
    }
}
