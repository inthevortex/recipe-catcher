package org.eu.angsumanray.recipecatcher.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** USD prices used by the AI spend panel; applied to the whole usage history when shown. */
data class PriceTable(
    val inputPerMillion: Double = SettingsStore.DEFAULT_INPUT_PRICE_PER_M,
    val outputPerMillion: Double = SettingsStore.DEFAULT_OUTPUT_PRICE_PER_M,
    val cacheReadPerMillion: Double = SettingsStore.DEFAULT_CACHE_READ_PRICE_PER_M,
    val perWebSearch: Double = SettingsStore.DEFAULT_WEB_SEARCH_PRICE
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secretStore: SecretStore
) {
    companion object {
        const val DEFAULT_MODEL_ID = "claude-haiku-5-5"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_SYSTEM = "system"

        // Default Haiku 5.5 pricing per million tokens in USD
        const val DEFAULT_INPUT_PRICE_PER_M = 0.10
        const val DEFAULT_OUTPUT_PRICE_PER_M = 0.50
        const val DEFAULT_CACHE_READ_PRICE_PER_M = 0.01
        const val DEFAULT_WEB_SEARCH_PRICE = 0.01
        const val DEFAULT_WEEK_STARTS_ON = "Monday"
        const val DEFAULT_PREP_ALERT_TIME = "21:00"

        private val KEY_MODEL_ID = stringPreferencesKey("model_id")
        private val KEY_THEME = stringPreferencesKey("app_theme")
        private val KEY_PRICE_INPUT_M = doublePreferencesKey("price_input_m")
        private val KEY_PRICE_OUTPUT_M = doublePreferencesKey("price_output_m")
        private val KEY_PRICE_CACHE_M = doublePreferencesKey("price_cache_m")
        private val KEY_PRICE_WEB = doublePreferencesKey("price_web_search")
        private val KEY_ALLOW_NEW_TAGS = androidx.datastore.preferences.core.booleanPreferencesKey("allow_new_ai_tags")

        private val KEY_LAST_BACKUP_AT = stringPreferencesKey("last_backup_at")
        private val KEY_NEW_RECIPES_SINCE_BACKUP = androidx.datastore.preferences.core.intPreferencesKey("new_recipes_since_backup")
        private val KEY_BACKUP_REMINDER_THRESHOLD = androidx.datastore.preferences.core.intPreferencesKey("backup_reminder_threshold")
        private val KEY_REMEMBER_BACKUP_FOLDER = androidx.datastore.preferences.core.booleanPreferencesKey("remember_backup_folder")
        private val KEY_BACKUP_FOLDER_URI = stringPreferencesKey("backup_folder_uri")

        private val KEY_WEEK_STARTS_ON = stringPreferencesKey("week_starts_on")
        private val KEY_PREP_ALERT_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("prep_alert_enabled")
        private val KEY_PREP_ALERT_TIME = stringPreferencesKey("prep_alert_time")
        private val KEY_COOKING_ALERTS_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("cooking_alerts")
        private val KEY_CHAT_MODEL_ID = stringPreferencesKey("chat_model_id")
        private val KEY_INCLUDE_WEB_DEFAULT = androidx.datastore.preferences.core.booleanPreferencesKey("include_web_default")
        private val KEY_MAX_WEB_SEARCHES = androidx.datastore.preferences.core.intPreferencesKey("max_web_searches")
        const val DEFAULT_MAX_WEB_SEARCHES = 3
        const val MIN_WEB_SEARCHES = 1
        const val MAX_WEB_SEARCHES = 10
    }

    val weekStartsOnFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_WEEK_STARTS_ON] ?: DEFAULT_WEEK_STARTS_ON
    }

    val prepAlertEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_PREP_ALERT_ENABLED] ?: true
    }

    val prepAlertTimeFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_PREP_ALERT_TIME] ?: DEFAULT_PREP_ALERT_TIME
    }

    val cookingAlertsEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_COOKING_ALERTS_ENABLED] ?: true
    }

    suspend fun setWeekStartsOn(day: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_WEEK_STARTS_ON] = day
        }
    }

    suspend fun setPrepAlertEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_PREP_ALERT_ENABLED] = enabled
        }
    }

    suspend fun setPrepAlertTime(time: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_PREP_ALERT_TIME] = time
        }
    }

    suspend fun setCookingAlertsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_COOKING_ALERTS_ENABLED] = enabled
        }
    }

    val lastBackupAtFlow: Flow<String?> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_LAST_BACKUP_AT]
    }

    val newRecipesSinceBackupFlow: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_NEW_RECIPES_SINCE_BACKUP] ?: 0
    }

    val backupReminderThresholdFlow: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_BACKUP_REMINDER_THRESHOLD] ?: 10
    }

    val rememberBackupFolderFlow: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_REMEMBER_BACKUP_FOLDER] ?: true
    }

    val backupFolderUriFlow: Flow<String?> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_BACKUP_FOLDER_URI]
    }

    suspend fun setLastBackupAt(timestamp: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_LAST_BACKUP_AT] = timestamp
        }
    }

    suspend fun incrementNewRecipesSinceBackup() {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[KEY_NEW_RECIPES_SINCE_BACKUP] ?: 0
            prefs[KEY_NEW_RECIPES_SINCE_BACKUP] = current + 1
        }
    }

    suspend fun resetNewRecipesSinceBackup() {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_NEW_RECIPES_SINCE_BACKUP] = 0
        }
    }

    suspend fun setBackupReminderThreshold(threshold: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_BACKUP_REMINDER_THRESHOLD] = threshold
        }
    }

    suspend fun setRememberBackupFolder(remember: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_REMEMBER_BACKUP_FOLDER] = remember
        }
    }

    suspend fun setBackupFolderUri(uri: String?) {
        context.settingsDataStore.edit { prefs ->
            if (uri != null) {
                prefs[KEY_BACKUP_FOLDER_URI] = uri
            } else {
                prefs.remove(KEY_BACKUP_FOLDER_URI)
            }
        }
    }

    val modelIdFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_MODEL_ID] ?: DEFAULT_MODEL_ID
    }

    val chatModelIdFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_CHAT_MODEL_ID]?.takeIf { it.isNotBlank() }
            ?: prefs[KEY_MODEL_ID]?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL_ID
    }

    val includeWebDefaultFlow: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_INCLUDE_WEB_DEFAULT] ?: false
    }

    val maxWebSearchesFlow: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        (prefs[KEY_MAX_WEB_SEARCHES] ?: DEFAULT_MAX_WEB_SEARCHES).coerceIn(MIN_WEB_SEARCHES, MAX_WEB_SEARCHES)
    }

    /** The chat model as typed in Settings; blank means "same as the extraction model". */
    val chatModelIdSettingFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_CHAT_MODEL_ID].orEmpty()
    }

    suspend fun setChatModelId(modelId: String) {
        context.settingsDataStore.edit { prefs ->
            val trimmed = modelId.trim()
            if (trimmed.isEmpty()) prefs.remove(KEY_CHAT_MODEL_ID) else prefs[KEY_CHAT_MODEL_ID] = trimmed
        }
    }

    suspend fun setIncludeWebDefault(include: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_INCLUDE_WEB_DEFAULT] = include
        }
    }

    suspend fun setMaxWebSearches(max: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_MAX_WEB_SEARCHES] = max.coerceIn(MIN_WEB_SEARCHES, MAX_WEB_SEARCHES)
        }
    }

    val themeFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_THEME] ?: THEME_DARK
    }

    val allowNewAiTagsFlow: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_ALLOW_NEW_TAGS] ?: false
    }

    suspend fun setAllowNewAiTags(allow: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_ALLOW_NEW_TAGS] = allow
        }
    }

    val priceTableFlow: Flow<PriceTable> = context.settingsDataStore.data.map { prefs ->
        PriceTable(
            inputPerMillion = prefs[KEY_PRICE_INPUT_M] ?: DEFAULT_INPUT_PRICE_PER_M,
            outputPerMillion = prefs[KEY_PRICE_OUTPUT_M] ?: DEFAULT_OUTPUT_PRICE_PER_M,
            cacheReadPerMillion = prefs[KEY_PRICE_CACHE_M] ?: DEFAULT_CACHE_READ_PRICE_PER_M,
            perWebSearch = prefs[KEY_PRICE_WEB] ?: DEFAULT_WEB_SEARCH_PRICE
        )
    }

    suspend fun setPriceTable(prices: PriceTable) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_PRICE_INPUT_M] = prices.inputPerMillion
            prefs[KEY_PRICE_OUTPUT_M] = prices.outputPerMillion
            prefs[KEY_PRICE_CACHE_M] = prices.cacheReadPerMillion
            prefs[KEY_PRICE_WEB] = prices.perWebSearch
        }
    }

    suspend fun setModelId(modelId: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_MODEL_ID] = modelId.trim().ifEmpty { DEFAULT_MODEL_ID }
        }
    }

    suspend fun setTheme(theme: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_THEME] = theme
        }
    }

    suspend fun saveApiKey(apiKey: String) {
        secretStore.saveApiKey(apiKey)
    }

    suspend fun getApiKey(): String {
        return secretStore.getApiKey()
    }

    fun calculateSpend(
        inputTokens: Long,
        outputTokens: Long,
        cacheReadTokens: Long,
        webSearches: Long = 0,
        prices: PriceTable = PriceTable()
    ): Double {
        val inputCost = (inputTokens.toDouble() / 1_000_000.0) * prices.inputPerMillion
        val outputCost = (outputTokens.toDouble() / 1_000_000.0) * prices.outputPerMillion
        val cacheCost = (cacheReadTokens.toDouble() / 1_000_000.0) * prices.cacheReadPerMillion
        val webCost = webSearches * prices.perWebSearch
        return inputCost + outputCost + cacheCost + webCost
    }
}
