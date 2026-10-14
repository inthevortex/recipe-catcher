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
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
            webSearchProvider = dummySearchProvider
        )

        chatRepository = ChatRepository(
            context = context,
            database = database,
            chatPlanner = chatPlanner,
            settingsStore = settingsStore,
            recipeRepository = recipeRepository
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
}
