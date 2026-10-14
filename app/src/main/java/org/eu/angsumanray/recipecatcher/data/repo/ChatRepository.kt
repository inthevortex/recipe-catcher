package org.eu.angsumanray.recipecatcher.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.capture.CaptureWorker
import org.eu.angsumanray.recipecatcher.core.llm.ChatPlanResult
import org.eu.angsumanray.recipecatcher.core.llm.ChatPlanner
import org.eu.angsumanray.recipecatcher.core.llm.RecipeIndexEntry
import org.eu.angsumanray.recipecatcher.core.model.ChatMessage
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.ChatMessageEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.ChatSessionEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.LlmUsageEntity
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: RecipeDatabase,
    private val chatPlanner: ChatPlanner,
    private val settingsStore: SettingsStore,
    private val recipeRepository: RecipeRepository
) {
    private val chatDao = database.chatDao()
    private val recipeDao = database.recipeDao()
    private val ingredientDao = database.ingredientDao()
    private val recipeTagDao = database.recipeTagDao()
    private val cookLogDao = database.cookLogDao()
    private val llmUsageDao = database.llmUsageDao()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    suspend fun getOrCreateDefaultSession(): Long {
        val first = chatDao.getFirstSession()
        if (first != null) return first.id
        return chatDao.insertSession(ChatSessionEntity(title = "Default Session"))
    }

    fun observeMessages(sessionId: Long): Flow<List<ChatMessage>> {
        return chatDao.observeMessagesForSession(sessionId).map { list ->
            list.map { entity ->
                ChatMessage(
                    id = entity.id,
                    sessionId = entity.sessionId,
                    role = entity.role,
                    text = entity.text,
                    includeWeb = entity.includeWeb,
                    suggestionsJson = entity.suggestionsJson,
                    createdAt = entity.createdAt
                )
            }
        }
    }

    suspend fun sendMessage(
        sessionId: Long,
        userText: String,
        includeWeb: Boolean
    ): Result<ChatMessage> {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Message cannot be empty"))

        // 1. Insert user message into database
        val userEntity = ChatMessageEntity(
            sessionId = sessionId,
            role = ChatMessageEntity.ROLE_USER,
            text = trimmed,
            includeWeb = includeWeb,
            createdAt = System.currentTimeMillis()
        )
        chatDao.insertMessage(userEntity)

        // 2. Build RecipeIndexEntry list from saved recipes
        val savedRecipes = recipeDao.getAllSaved()
        val indexEntries = savedRecipes.map { recipe ->
            val ingredients = ingredientDao.getByRecipeId(recipe.id)
            val tags = recipeTagDao.getTagsForRecipe(recipe.id)
            val lastCookedDate = cookLogDao.getLastCookedDate(recipe.id)
            val mainIngredients = ingredients.map { it.name }.filter { it.isNotBlank() }
            val tagNames = tags.map { it.name }

            RecipeIndexEntry(
                id = recipe.id,
                title = recipe.title.orEmpty(),
                tags = tagNames,
                mainIngredients = mainIngredients,
                totalMinutes = recipe.totalMin,
                complexity = recipe.complexity,
                spiceLevel = recipe.spiceLevel,
                dietType = recipe.dietType,
                contains = emptyList(),
                kcalPerServing = recipe.kcalPerServing,
                makeAhead = recipe.makeAhead,
                freezerFriendly = recipe.freezerFriendly,
                equipment = emptyList(),
                favourite = recipe.isFavourite,
                lastCookedDate = lastCookedDate
            )
        }

        // 3. Load last 10 messages for conversation history
        val lastEntities = chatDao.getLastMessagesForSession(sessionId, limit = 10)
        // Reverse so it's in chronological order
        val history = lastEntities.reversed().map { entity ->
            ChatMessage(
                id = entity.id,
                sessionId = entity.sessionId,
                role = entity.role,
                text = entity.text,
                includeWeb = entity.includeWeb,
                suggestionsJson = entity.suggestionsJson,
                createdAt = entity.createdAt
            )
        }

        val maxSearches = settingsStore.maxWebSearchesFlow.first()

        // 4. Call ChatPlanner
        val planResult = chatPlanner.planChat(
            userMessage = trimmed,
            history = history,
            libraryRecipes = indexEntries,
            includeWeb = includeWeb,
            maxWebSearches = maxSearches
        )

        if (planResult.isFailure) {
            return Result.failure(planResult.exceptionOrNull() ?: RuntimeException("Chat planner failed"))
        }

        val result = planResult.getOrThrow()
        val suggestionsJson = json.encodeToString(result)

        // 5. Insert assistant response
        val assistantEntity = ChatMessageEntity(
            sessionId = sessionId,
            role = ChatMessageEntity.ROLE_ASSISTANT,
            text = result.reply,
            includeWeb = includeWeb,
            suggestionsJson = suggestionsJson,
            createdAt = System.currentTimeMillis()
        )
        val assistantMsgId = chatDao.insertMessage(assistantEntity)

        // 6. Record LLM usage in DB
        val fallbackModel = settingsStore.chatModelIdFlow.first()
        if (result.usage.inputTokens > 0 || result.usage.outputTokens > 0) {
            llmUsageDao.insert(
                LlmUsageEntity(
                    purpose = LlmUsageEntity.PURPOSE_CHAT,
                    model = result.usage.model ?: fallbackModel,
                    inputTokens = result.usage.inputTokens,
                    outputTokens = result.usage.outputTokens,
                    cacheReadTokens = result.usage.cacheReadTokens,
                    webSearches = 0
                )
            )
        }
        if (result.usage.webSearches > 0) {
            llmUsageDao.insert(
                LlmUsageEntity(
                    purpose = LlmUsageEntity.PURPOSE_WEB,
                    model = result.usage.model ?: fallbackModel,
                    inputTokens = 0,
                    outputTokens = 0,
                    cacheReadTokens = 0,
                    webSearches = result.usage.webSearches
                )
            )
        }

        return Result.success(
            ChatMessage(
                id = assistantMsgId,
                sessionId = sessionId,
                role = ChatMessageEntity.ROLE_ASSISTANT,
                text = result.reply,
                includeWeb = includeWeb,
                suggestionsJson = suggestionsJson,
                createdAt = assistantEntity.createdAt
            )
        )
    }

    suspend fun clearMessages(sessionId: Long) {
        chatDao.clearMessagesForSession(sessionId)
    }

    suspend fun saveWebRecipe(url: String): Long? {
        val captureStart = recipeRepository.startCapture(url)
        return when (captureStart) {
            is CaptureStart.Created -> {
                CaptureWorker.enqueue(context, captureStart.recipeId)
                captureStart.recipeId
            }
            is CaptureStart.Existing -> captureStart.recipeId
            CaptureStart.NoLink -> null
        }
    }
}
