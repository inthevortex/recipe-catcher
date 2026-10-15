package org.eu.angsumanray.recipecatcher.ui.settings

import android.net.Uri
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
import org.eu.angsumanray.recipecatcher.backup.BackupInspection
import org.eu.angsumanray.recipecatcher.backup.BackupManager
import org.eu.angsumanray.recipecatcher.backup.BackupManifest
import org.eu.angsumanray.recipecatcher.backup.RestoreManager
import org.eu.angsumanray.recipecatcher.core.llm.ClaudeExtractor
import org.eu.angsumanray.recipecatcher.data.db.dao.LlmUsageDao
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import org.eu.angsumanray.recipecatcher.di.AnthropicHttpClient
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

sealed interface TestKeyStatus {
    object Idle : TestKeyStatus
    object Testing : TestKeyStatus
    data class Success(val message: String = "API Key is valid!") : TestKeyStatus
    data class Error(val message: String) : TestKeyStatus
}

sealed interface BackupOpStatus {
    object Idle : BackupOpStatus
    data class InProgress(val message: String) : BackupOpStatus
    data class Success(val message: String) : BackupOpStatus
    data class Error(val message: String) : BackupOpStatus
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
    private val backupManager: BackupManager,
    private val restoreManager: RestoreManager,
    @AnthropicHttpClient private val httpClient: HttpClient
) : ViewModel() {

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    val modelId: StateFlow<String> = settingsStore.modelIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.DEFAULT_MODEL_ID)

    val chatModelId: StateFlow<String> = settingsStore.chatModelIdSettingFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val includeWebDefault: StateFlow<Boolean> = settingsStore.includeWebDefaultFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val maxWebSearches: StateFlow<Int> = settingsStore.maxWebSearchesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.DEFAULT_MAX_WEB_SEARCHES)

    val theme: StateFlow<String> = settingsStore.themeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.THEME_DARK)

    val lastBackupAt: StateFlow<String?> = settingsStore.lastBackupAtFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val newRecipesSinceBackup: StateFlow<Int> = settingsStore.newRecipesSinceBackupFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val backupReminderThreshold: StateFlow<Int> = settingsStore.backupReminderThresholdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 10)

    val rememberBackupFolder: StateFlow<Boolean> = settingsStore.rememberBackupFolderFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val backupFolderUri: StateFlow<String?> = settingsStore.backupFolderUriFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val weekStartsOn: StateFlow<String> = settingsStore.weekStartsOnFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.DEFAULT_WEEK_STARTS_ON)

    val prepAlertEnabled: StateFlow<Boolean> = settingsStore.prepAlertEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val prepAlertTime: StateFlow<String> = settingsStore.prepAlertTimeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.DEFAULT_PREP_ALERT_TIME)

    val cookingAlertsEnabled: StateFlow<Boolean> = settingsStore.cookingAlertsEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val isInstagramLoggedIn: StateFlow<Boolean> = settingsStore.instagramLoggedInFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val fetchTopComment: StateFlow<Boolean> = settingsStore.fetchTopCommentFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setFetchTopComment(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setFetchTopComment(enabled)
        }
    }

    fun logoutInstagram() {
        viewModelScope.launch {
            android.webkit.CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            settingsStore.setInstagramLoggedIn(false)
        }
    }

    fun setWeekStartsOn(day: String) {
        viewModelScope.launch {
            settingsStore.setWeekStartsOn(day)
        }
    }

    fun setPrepAlertEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setPrepAlertEnabled(enabled)
        }
    }

    fun setPrepAlertTime(time: String) {
        viewModelScope.launch {
            settingsStore.setPrepAlertTime(time)
        }
    }

    fun setCookingAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setCookingAlertsEnabled(enabled)
        }
    }

    private val _testKeyStatus = MutableStateFlow<TestKeyStatus>(TestKeyStatus.Idle)
    val testKeyStatus: StateFlow<TestKeyStatus> = _testKeyStatus.asStateFlow()

    private val _spendStats = MutableStateFlow(SpendStats())
    val spendStats: StateFlow<SpendStats> = _spendStats.asStateFlow()

    private val _backupOpStatus = MutableStateFlow<BackupOpStatus>(BackupOpStatus.Idle)
    val backupOpStatus: StateFlow<BackupOpStatus> = _backupOpStatus.asStateFlow()

    private val _pendingRestoreInspection = MutableStateFlow<BackupInspection?>(null)
    val pendingRestoreInspection: StateFlow<BackupInspection?> = _pendingRestoreInspection.asStateFlow()

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

    fun setChatModelId(modelId: String) {
        viewModelScope.launch {
            settingsStore.setChatModelId(modelId)
        }
    }

    fun setIncludeWebDefault(include: Boolean) {
        viewModelScope.launch {
            settingsStore.setIncludeWebDefault(include)
        }
    }

    fun setMaxWebSearches(max: Int) {
        viewModelScope.launch {
            settingsStore.setMaxWebSearches(max)
        }
    }

    fun setTheme(newTheme: String) {
        viewModelScope.launch {
            settingsStore.setTheme(newTheme)
        }
    }

    fun setRememberBackupFolder(remember: Boolean) {
        viewModelScope.launch {
            settingsStore.setRememberBackupFolder(remember)
            if (!remember) {
                settingsStore.backupFolderUriFlow.first()?.let { backupManager.releaseFolderAccess(Uri.parse(it)) }
                settingsStore.setBackupFolderUri(null)
            }
        }
    }

    /**
     * Backs up straight into the remembered folder when there is one, otherwise asks for a folder
     * (remembering on) or for a file (remembering off).
     */
    fun backupNow(pickFile: () -> Unit, pickFolder: () -> Unit) {
        viewModelScope.launch {
            if (!settingsStore.rememberBackupFolderFlow.first()) {
                pickFile()
                return@launch
            }
            val folder = settingsStore.backupFolderUriFlow.first()?.let(Uri::parse)
            if (folder != null && backupManager.hasFolderAccess(folder)) {
                backupToFolder(folder)
            } else {
                pickFolder()
            }
        }
    }

    fun onBackupFolderPicked(treeUri: Uri) {
        viewModelScope.launch {
            try {
                backupManager.takeFolderAccess(treeUri)
                settingsStore.setBackupFolderUri(treeUri.toString())
            } catch (e: SecurityException) {
                _backupOpStatus.value = BackupOpStatus.Error("Backup failed: no access to that folder")
                return@launch
            }
            backupToFolder(treeUri)
        }
    }

    private suspend fun backupToFolder(treeUri: Uri) {
        _backupOpStatus.value = BackupOpStatus.InProgress("Creating backup...")
        try {
            val (documentUri, manifest) = backupManager.backupToFolder(treeUri)
            _backupOpStatus.value = BackupOpStatus.Success(backupSuccessMessage(documentUri, manifest))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is SecurityException) settingsStore.setBackupFolderUri(null)
            _backupOpStatus.value = BackupOpStatus.Error("Backup failed: ${e.message}")
        }
    }

    private fun backupSuccessMessage(documentUri: Uri, manifest: BackupManifest): String {
        val name = backupManager.displayName(documentUri) ?: "Backup"
        val time = runCatching {
            OffsetDateTime.parse(manifest.createdAt).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
        }.getOrDefault(manifest.createdAt)
        return "Saved $name at $time: ${manifest.recipeCount} recipes and ${manifest.imageCount} images."
    }

    fun setBackupReminderThreshold(threshold: Int) {
        viewModelScope.launch {
            settingsStore.setBackupReminderThreshold(threshold)
        }
    }

    fun setBackupFolderUri(uri: String?) {
        viewModelScope.launch {
            settingsStore.setBackupFolderUri(uri)
        }
    }

    fun clearBackupOpStatus() {
        _backupOpStatus.value = BackupOpStatus.Idle
    }

    fun backupToUri(uri: Uri) {
        viewModelScope.launch {
            _backupOpStatus.value = BackupOpStatus.InProgress("Creating backup...")
            try {
                val manifest = backupManager.backupToUri(uri)
                _backupOpStatus.value = BackupOpStatus.Success(backupSuccessMessage(uri, manifest))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _backupOpStatus.value = BackupOpStatus.Error("Backup failed: ${e.message}")
            }
        }
    }

    fun inspectBackup(uri: Uri) {
        viewModelScope.launch {
            _backupOpStatus.value = BackupOpStatus.InProgress("Inspecting backup file...")
            try {
                val inspection = restoreManager.inspectBackup(uri)
                _pendingRestoreInspection.value = inspection
                _backupOpStatus.value = BackupOpStatus.Idle
            } catch (e: Exception) {
                _backupOpStatus.value = BackupOpStatus.Error("Backup inspection failed: ${e.message}")
            }
        }
    }

    fun dismissRestoreDialog() {
        _pendingRestoreInspection.value?.tempDir?.deleteRecursively()
        _pendingRestoreInspection.value = null
    }

    fun confirmRestore(restartProcess: Boolean = true) {
        val inspection = _pendingRestoreInspection.value ?: return
        viewModelScope.launch {
            _backupOpStatus.value = BackupOpStatus.InProgress("Restoring recipes & images...")
            try {
                restoreManager.performRestore(inspection, restartProcess = restartProcess)
                _pendingRestoreInspection.value = null
                _backupOpStatus.value = BackupOpStatus.Success("Restore complete!")
            } catch (e: Exception) {
                _backupOpStatus.value = BackupOpStatus.Error("Restore failed: ${e.message}")
            }
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
