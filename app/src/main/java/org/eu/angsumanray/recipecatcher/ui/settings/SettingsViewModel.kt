package org.eu.angsumanray.recipecatcher.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.eu.angsumanray.recipecatcher.core.llm.ClaudeExtractor
import org.eu.angsumanray.recipecatcher.data.db.dao.LlmUsageDao
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.util.Locale
import javax.inject.Inject

sealed interface TestKeyStatus {
    object Idle : TestKeyStatus
    object Testing : TestKeyStatus
    data class Success(val message: String = "API Key is valid!") : TestKeyStatus
    data class Error(val message: String) : TestKeyStatus
}

data class SpendStats(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val estimatedCost: Double = 0.0
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val llmUsageDao: LlmUsageDao,
    private val httpClient: HttpClient
) : ViewModel() {

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    val modelId: StateFlow<String> = settingsStore.modelIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.DEFAULT_MODEL_ID)

    val theme: StateFlow<String> = settingsStore.themeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.THEME_DARK)

    private val _testKeyStatus = MutableStateFlow<TestKeyStatus>(TestKeyStatus.Idle)
    val testKeyStatus: StateFlow<TestKeyStatus> = _testKeyStatus.asStateFlow()

    private val _spendStats = MutableStateFlow(SpendStats())
    val spendStats: StateFlow<SpendStats> = _spendStats.asStateFlow()

    init {
        loadSettings()
        loadSpendStats()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            _apiKey.value = settingsStore.getApiKey()
        }
    }

    fun loadSpendStats() {
        viewModelScope.launch {
            val input = llmUsageDao.getTotalInputTokens() ?: 0L
            val output = llmUsageDao.getTotalOutputTokens() ?: 0L
            val cache = llmUsageDao.getTotalCacheReadTokens() ?: 0L
            val cost = settingsStore.calculateSpend(input, output, cache)
            _spendStats.value = SpendStats(
                inputTokens = input,
                outputTokens = output,
                cacheReadTokens = cache,
                estimatedCost = cost
            )
        }
    }

    fun updateApiKeyText(key: String) {
        _apiKey.value = key
        _testKeyStatus.value = TestKeyStatus.Idle
    }

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            settingsStore.saveApiKey(key.trim())
            _apiKey.value = key.trim()
        }
    }

    fun setModelId(id: String) {
        viewModelScope.launch {
            settingsStore.setModelId(id)
        }
    }

    fun setTheme(newTheme: String) {
        viewModelScope.launch {
            settingsStore.setTheme(newTheme)
        }
    }

    fun testApiKey() {
        val key = _apiKey.value.trim()
        if (key.isEmpty()) {
            _testKeyStatus.value = TestKeyStatus.Error("Please enter an API key first")
            return
        }

        _testKeyStatus.value = TestKeyStatus.Testing
        viewModelScope.launch {
            try {
                val pingPayload = buildJsonObject {
                    put("model", modelId.value)
                    put("max_tokens", 1)
                    putJsonArray("messages") {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", "ping")
                        })
                    }
                }.toString()

                val response = httpClient.post(ClaudeExtractor.ANTHROPIC_MESSAGES_URL) {
                    contentType(ContentType.Application.Json)
                    header("x-api-key", key)
                    header("anthropic-version", ClaudeExtractor.ANTHROPIC_VERSION)
                    setBody(pingPayload)
                }

                if (response.status.value in 200..299) {
                    _testKeyStatus.value = TestKeyStatus.Success("API Key verified successfully!")
                    settingsStore.saveApiKey(key)
                } else {
                    _testKeyStatus.value = TestKeyStatus.Error("HTTP ${response.status.value}: Key verification failed")
                }
            } catch (e: Exception) {
                _testKeyStatus.value = TestKeyStatus.Error(e.message ?: "Connection error")
            }
        }
    }
}
