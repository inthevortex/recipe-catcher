package org.eu.angsumanray.recipecatcher.data.repo

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.capture.CaptureWorker
import org.eu.angsumanray.recipecatcher.core.llm.ChatPlanResult
import org.eu.angsumanray.recipecatcher.core.llm.ChatPlanner
import org.eu.angsumanray.recipecatcher.core.llm.LibraryEditProposal
import org.eu.angsumanray.recipecatcher.core.llm.LibraryEditTools
import org.eu.angsumanray.recipecatcher.core.llm.LibraryRecipe
import org.eu.angsumanray.recipecatcher.core.llm.TagRef
import org.eu.angsumanray.recipecatcher.core.llm.LlmUsage
import org.eu.angsumanray.recipecatcher.core.llm.WebPicks
import org.eu.angsumanray.recipecatcher.core.llm.llmErrorMessage
import org.eu.angsumanray.recipecatcher.core.model.ChatMessage
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.ChatMessageEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.ChatSessionEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.LlmUsageEntity
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: RecipeDatabase,
    private val chatPlanner: ChatPlanner,
    private val settingsStore: SettingsStore,
    private val recipeRepository: RecipeRepository,
    private val recipeIndexBuilder: RecipeIndexBuilder,
    private val libraryEditor: LibraryEditor
) {
    private val chatDao = database.chatDao()
    private val llmUsageDao = database.llmUsageDao()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    companion object {
        const val DEFAULT_SESSION_TITLE = "New chat"
        private const val TITLE_MAX_LENGTH = 60
    }

    suspend fun getOrCreateDefaultSession(): Long {
        val first = chatDao.getFirstSession()
        if (first != null) return first.id
        return chatDao.insertSession(ChatSessionEntity(title = DEFAULT_SESSION_TITLE))
    }

    fun observeMessages(sessionId: Long): Flow<List<ChatMessage>> =
        chatDao.observeMessagesForSession(sessionId).map { list -> list.map { it.toModel() } }

    /**
     * Saves the user's message, then runs the library call and (when [includeWeb] is on) the web call
     * in parallel. The assistant reply is saved as soon as the library call returns; web cards are
     * added to that same message when the web call finishes.
     */
    suspend fun sendMessage(
        sessionId: Long,
        userText: String,
        includeWeb: Boolean
    ): Result<ChatMessage> = coroutineScope {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return@coroutineScope Result.failure(IllegalArgumentException("Message cannot be empty"))

        // History is read before the new message is stored, so it isn't sent twice.
        val history = chatDao.getLastMessagesForSession(sessionId, limit = ChatPlanner.HISTORY_LIMIT)
            .reversed()
            .map { it.toModel() }

        val now = System.currentTimeMillis()
        database.withTransaction {
            chatDao.insertMessage(
                ChatMessageEntity(
                    sessionId = sessionId,
                    role = ChatMessageEntity.ROLE_USER,
                    text = trimmed,
                    includeWeb = includeWeb,
                    createdAt = now
                )
            )
            chatDao.touchSession(sessionId, now)
            val session = chatDao.getSession(sessionId)
            if (session != null && session.title == DEFAULT_SESSION_TITLE) {
                chatDao.updateSessionTitle(sessionId, trimmed.take(TITLE_MAX_LENGTH))
            }
        }

        val maxSearches = settingsStore.maxWebSearchesFlow.first()
        val web = if (includeWeb) async { chatPlanner.searchWeb(trimmed, maxSearches) } else null

        val libraryResult = try {
            val index = recipeIndexBuilder.build()
            val ftsHits = if (index.size > ChatPlanner.PRE_FILTER_THRESHOLD) {
                recipeIndexBuilder.ftsHits(ChatPlanner.searchTerms(trimmed))
            } else {
                emptyMap()
            }
            chatPlanner.planLibrary(
                userMessage = trimmed,
                history = history,
                libraryRecipes = index,
                ftsHits = ftsHits,
                today = LocalDate.now().toString(),
                editTools = editTools()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

        if (libraryResult.isFailure) {
            web?.cancel()
            return@coroutineScope Result.failure(libraryResult.exceptionOrNull() ?: RuntimeException("Chat planner failed"))
        }

        val plan = libraryResult.getOrThrow()
        val fallbackModel = settingsStore.chatModelIdFlow.first()
        val assistantEntity = ChatMessageEntity(
            sessionId = sessionId,
            role = ChatMessageEntity.ROLE_ASSISTANT,
            text = plan.reply,
            includeWeb = includeWeb,
            suggestionsJson = json.encodeToString(plan),
            createdAt = maxOf(System.currentTimeMillis(), now + 1)
        )
        val assistantMsgId = database.withTransaction {
            val id = chatDao.insertMessage(assistantEntity)
            logUsage(LlmUsageEntity.PURPOSE_CHAT, plan.usage, fallbackModel)
            chatDao.touchSession(sessionId, assistantEntity.createdAt)
            id
        }

        var finalPlan = plan
        if (web != null) {
            val webResult = try {
                web.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure<WebPicks>(e)
            }
            finalPlan = webResult.fold(
                onSuccess = { plan.copy(webSuggestions = it.picks, webUsage = it.usage) },
                onFailure = { plan.copy(webNote = "Web search failed: ${llmErrorMessage(it)}") }
            )
            database.withTransaction {
                chatDao.updateSuggestions(assistantMsgId, json.encodeToString(finalPlan))
                finalPlan.webUsage?.let { logUsage(LlmUsageEntity.PURPOSE_WEB, it, fallbackModel) }
            }
        }

        Result.success(
            assistantEntity.copy(id = assistantMsgId, suggestionsJson = json.encodeToString(finalPlan)).toModel()
        )
    }

    /**
     * Lets chat search the whole library, read ingredients and see existing tag names when
     * proposing changes. The library is read in four queries, only once chat uses a tool.
     */
    private suspend fun editTools(): LibraryEditTools {
        val allTags = database.tagDao().getAll()
        val tagNames = allTags.groupBy { it.kind }.mapValues { (_, tags) -> tags.map { it.name } }
        return LibraryEditTools(tagNames) {
            val tagsById = allTags.associateBy { it.id }
            val tagsByRecipe = database.recipeTagDao().getAll().groupBy({ it.recipeId }) { link ->
                tagsById[link.tagId]?.let { TagRef(it.kind, it.name) }
            }
            val ingredientsByRecipe = database.ingredientDao().getAll().groupBy({ it.recipeId }) { it.name }
            database.recipeDao().getAllSaved().map { recipe ->
                LibraryRecipe(
                    id = recipe.id,
                    title = recipe.title.orEmpty(),
                    ingredients = ingredientsByRecipe[recipe.id].orEmpty(),
                    tags = tagsByRecipe[recipe.id].orEmpty().filterNotNull(),
                    dietType = recipe.dietType,
                    complexity = recipe.complexity,
                    spiceLevel = recipe.spiceLevel,
                    totalMinutes = recipe.totalMin
                )
            }
        }
    }

    /** Applies the changes proposed in message [messageId], except for [skippedRecipeIds]. */
    suspend fun applyEdits(messageId: Long, skippedRecipeIds: Set<Long>): Result<Int> =
        updateProposal(messageId, LibraryEditProposal.STATUS_PROPOSED) { libraryEditor.apply(it, skippedRecipeIds) }
            .map { it.applied.size }

    /** Reverts the changes applied from message [messageId]. */
    suspend fun undoEdits(messageId: Long): Result<Int> =
        updateProposal(messageId, LibraryEditProposal.STATUS_APPLIED) { libraryEditor.undo(it) }
            .map { 0 }

    suspend fun dismissEdits(messageId: Long): Result<Int> =
        updateProposal(messageId, LibraryEditProposal.STATUS_PROPOSED) { it.copy(status = LibraryEditProposal.STATUS_DISMISSED) }
            .map { 0 }

    /** Runs [change] on the proposal in [messageId] when it is in [expectedStatus], and stores the result. */
    private suspend fun updateProposal(
        messageId: Long,
        expectedStatus: String,
        change: suspend (LibraryEditProposal) -> LibraryEditProposal
    ): Result<LibraryEditProposal> {
        val message = chatDao.getMessage(messageId) ?: return Result.failure(IllegalStateException("Message not found"))
        val plan = message.suggestionsJson?.let { runCatching { json.decodeFromString<ChatPlanResult>(it) }.getOrNull() }
        val proposal = plan?.edits ?: return Result.failure(IllegalStateException("No changes in this message"))
        if (proposal.status != expectedStatus) return Result.failure(IllegalStateException("Changes are already ${proposal.status}"))
        val updated = change(proposal)
        chatDao.updateSuggestions(messageId, json.encodeToString(plan.copy(edits = updated)))
        return Result.success(updated)
    }

    suspend fun clearMessages(sessionId: Long) {
        chatDao.clearMessagesForSession(sessionId)
    }

    /** Hands a web pick's URL to the importer; returns the recipe ID, or null if it isn't a usable link. */
    suspend fun saveWebRecipe(url: String): Long? =
        when (val captureStart = recipeRepository.startCapture(url)) {
            is CaptureStart.Created -> {
                CaptureWorker.enqueue(context, captureStart.recipeId)
                captureStart.recipeId
            }
            is CaptureStart.Existing -> captureStart.recipeId
            CaptureStart.NoLink -> null
        }

    private suspend fun logUsage(purpose: String, usage: LlmUsage, fallbackModel: String) {
        if (usage.inputTokens == 0 && usage.outputTokens == 0 && usage.webSearches == 0) return
        llmUsageDao.insert(
            LlmUsageEntity(
                purpose = purpose,
                model = usage.model ?: fallbackModel,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                cacheReadTokens = usage.cacheReadTokens,
                webSearches = usage.webSearches
            )
        )
    }

    private fun ChatMessageEntity.toModel() = ChatMessage(
        id = id,
        sessionId = sessionId,
        role = role,
        text = text,
        includeWeb = includeWeb,
        suggestionsJson = suggestionsJson,
        createdAt = createdAt
    )
}
