package org.eu.angsumanray.recipecatcher.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.core.llm.ChatPlanResult
import org.eu.angsumanray.recipecatcher.core.llm.llmErrorMessage
import org.eu.angsumanray.recipecatcher.core.model.ChatMessage
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.repo.ChatRepository
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import javax.inject.Inject

data class ChatUiState(
    val isLoading: Boolean = true,
    val sessionId: Long = 0L,
    val messages: List<ChatMessage> = emptyList(),
    val inputMessage: String = "",
    val includeWeb: Boolean = false,
    val chatModelId: String = SettingsStore.CHAT_MODEL_HAIKU,
    val isSending: Boolean = false,
    val savingUrls: Set<String> = emptySet(),
    val errorMessage: String? = null,
    /** Titles of saved recipes; a cited ID missing here is no longer in the library. */
    val recipeTitles: Map<Long, String> = emptyMap(),
    /** Messages whose proposed library changes are being applied or undone. */
    val editBusyMessageIds: Set<Long> = emptySet()
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val settingsStore: SettingsStore,
    private val database: RecipeDatabase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        viewModelScope.launch {
            val defaultIncludeWeb = settingsStore.includeWebDefaultFlow.first()
            val sessionId = chatRepository.getOrCreateDefaultSession()
            _uiState.update { it.copy(isLoading = false, sessionId = sessionId, includeWeb = defaultIncludeWeb) }

            chatRepository.observeMessages(sessionId).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
        viewModelScope.launch {
            settingsStore.chatModelIdFlow.collect { modelId ->
                _uiState.update { it.copy(chatModelId = modelId) }
            }
        }
        viewModelScope.launch {
            database.recipeDao().observeAllSaved().collect { recipes ->
                _uiState.update { state ->
                    state.copy(recipeTitles = recipes.associate { it.id to (it.title ?: "Untitled Recipe") })
                }
            }
        }
    }

    fun setChatModelId(modelId: String) {
        viewModelScope.launch {
            settingsStore.setChatModelId(modelId)
        }
    }

    fun onInputMessageChange(newText: String) {
        _uiState.update { it.copy(inputMessage = newText) }
    }

    fun toggleIncludeWeb() {
        _uiState.update { it.copy(includeWeb = !it.includeWeb) }
    }

    /** Sends the typed message, or [presetMessage] (a quick prompt) without touching the draft. */
    fun sendMessage(presetMessage: String? = null) {
        val state = _uiState.value
        val messageToSend = (presetMessage ?: state.inputMessage).trim()
        if (messageToSend.isEmpty() || state.isSending || state.isLoading) return

        val fromDraft = presetMessage == null
        _uiState.update {
            it.copy(
                inputMessage = if (fromDraft) "" else it.inputMessage,
                isSending = true,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            val result = try {
                chatRepository.sendMessage(state.sessionId, messageToSend, state.includeWeb)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            _uiState.update {
                if (result.isSuccess) {
                    it.copy(isSending = false)
                } else {
                    it.copy(
                        isSending = false,
                        errorMessage = llmErrorMessage(result.exceptionOrNull()),
                        // Give the typed text back so it can be resent.
                        inputMessage = if (fromDraft && it.inputMessage.isEmpty()) messageToSend else it.inputMessage
                    )
                }
            }
        }
    }

    fun saveWebRecipe(url: String, onRecipeSaved: (Long) -> Unit) {
        if (url in _uiState.value.savingUrls) return
        _uiState.update { it.copy(savingUrls = it.savingUrls + url) }
        viewModelScope.launch {
            val recipeId = try {
                chatRepository.saveWebRecipe(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _uiState.update {
                it.copy(
                    savingUrls = it.savingUrls - url,
                    errorMessage = if (recipeId == null) "Could not save this web recipe" else it.errorMessage
                )
            }
            if (recipeId != null) onRecipeSaved(recipeId)
        }
    }

    /** Applies the changes proposed in [messageId], leaving out [skippedRecipeIds]. */
    fun applyEdits(messageId: Long, skippedRecipeIds: Set<Long>) =
        runEditAction(messageId) { chatRepository.applyEdits(messageId, skippedRecipeIds) }

    fun undoEdits(messageId: Long) = runEditAction(messageId) { chatRepository.undoEdits(messageId) }

    fun dismissEdits(messageId: Long) = runEditAction(messageId) { chatRepository.dismissEdits(messageId) }

    private fun runEditAction(messageId: Long, action: suspend () -> Result<Int>) {
        if (messageId in _uiState.value.editBusyMessageIds) return
        _uiState.update { it.copy(editBusyMessageIds = it.editBusyMessageIds + messageId) }
        viewModelScope.launch {
            val result = try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            _uiState.update {
                it.copy(
                    editBusyMessageIds = it.editBusyMessageIds - messageId,
                    errorMessage = result.exceptionOrNull()?.let { e -> e.message ?: "Couldn't update the library" } ?: it.errorMessage
                )
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            chatRepository.clearMessages(_uiState.value.sessionId)
        }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun parseSuggestions(jsonString: String?): ChatPlanResult? {
        if (jsonString.isNullOrBlank()) return null
        return try {
            json.decodeFromString<ChatPlanResult>(jsonString)
        } catch (e: Exception) {
            null
        }
    }
}
