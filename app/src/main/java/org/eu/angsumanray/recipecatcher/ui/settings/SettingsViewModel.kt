package org.eu.angsumanray.recipecatcher.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.core.llm.ClaudeExtractor
import org.eu.angsumanray.recipecatcher.data.db.dao.LlmUsageDao
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import org.eu.angsumanray.recipecatcher.di.AnthropicHttpClient
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
    @AnthropicHttpClient private val httpClient: HttpClient
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
            val webSearches = llmUsageDao.getTotalWebSearches() ?: 0L
            val cost = settingsStore.calculateSpend(input, output, cache, webSearches, settingsStore.priceTableFlow.first())
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
            // The Models API checks the key and the model ID without spending tokens.
            val model = modelId.value.trim().ifEmpty { SettingsStore.DEFAULT_MODEL_ID }
            _testKeyStatus.value = try {
                val response = httpClient.get("$MODELS_URL/$model") {
                    header("x-api-key", key)
                    header("anthropic-version", ClaudeExtractor.ANTHROPIC_VERSION)
                }
                when (response.status.value) {
                    in 200..299 -> {
                        settingsStore.saveApiKey(key)
                        TestKeyStatus.Success("API key works with $model")
                    }
                    401, 403 -> TestKeyStatus.Error("Check API key: HTTP ${response.status.value}")
                    404 -> {
                        settingsStore.saveApiKey(key)
                        TestKeyStatus.Error("Key works, but model \"$model\" was not found; update the model ID")
                    }
                    else -> TestKeyStatus.Error("HTTP ${response.status.value}: couldn't verify the key")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TestKeyStatus.Error(e.message ?: "Connection error")
            }
        }
    }

    private companion object {
        const val MODELS_URL = "https://api.anthropic.com/v1/models"
    }
}
