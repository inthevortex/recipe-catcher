package org.eu.angsumanray.recipecatcher.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.core.llm.ChatPlanResult
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
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val recipeTitles: Map<Long, String> = emptyMap()
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

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                sessionId = sessionId,
                includeWeb = defaultIncludeWeb
            )

            // Load all recipe titles for quick card lookup
            loadRecipeTitles()

            // Observe chat messages
            chatRepository.observeMessages(sessionId).collect { messages ->
                _uiState.value = _uiState.value.copy(messages = messages)
            }
        }
    }

    private suspend fun loadRecipeTitles() {
        val recipes = database.recipeDao().getAllSaved()
        val titlesMap = recipes.associate { it.id to (it.title ?: "Untitled Recipe") }
        _uiState.value = _uiState.value.copy(recipeTitles = titlesMap)
    }

    fun onInputMessageChange(newText: String) {
        _uiState.value = _uiState.value.copy(inputMessage = newText)
    }

    fun toggleIncludeWeb() {
        _uiState.value = _uiState.value.copy(includeWeb = !_uiState.value.includeWeb)
    }

    fun sendMessage(presetMessage: String? = null) {
        val messageToSend = (presetMessage ?: _uiState.value.inputMessage).trim()
        if (messageToSend.isEmpty() || _uiState.value.isSending) return

        val sessionId = _uiState.value.sessionId
        val includeWeb = _uiState.value.includeWeb

        _uiState.value = _uiState.value.copy(
            inputMessage = "",
            isSending = true,
            errorMessage = null
        )

        viewModelScope.launch {
            val result = chatRepository.sendMessage(sessionId, messageToSend, includeWeb)
            if (result.isFailure) {
                val error = result.exceptionOrNull()?.message ?: "Failed to send message"
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    errorMessage = error
                )
            } else {
                _uiState.value = _uiState.value.copy(isSending = false)
                loadRecipeTitles()
            }
        }
    }

    fun saveWebRecipe(url: String, onRecipeSaved: (Long) -> Unit) {
        viewModelScope.launch {
            val recipeId = chatRepository.saveWebRecipe(url)
            if (recipeId != null) {
                onRecipeSaved(recipeId)
            } else {
                _uiState.value = _uiState.value.copy(errorMessage = "Could not save web recipe")
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            val sessionId = _uiState.value.sessionId
            chatRepository.clearMessages(sessionId)
        }
    }

    fun clearErrorMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
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
