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

        private val KEY_MODEL_ID = stringPreferencesKey("model_id")
        private val KEY_THEME = stringPreferencesKey("app_theme")
        private val KEY_PRICE_INPUT_M = doublePreferencesKey("price_input_m")
        private val KEY_PRICE_OUTPUT_M = doublePreferencesKey("price_output_m")
        private val KEY_PRICE_CACHE_M = doublePreferencesKey("price_cache_m")
        private val KEY_PRICE_WEB = doublePreferencesKey("price_web_search")
        private val KEY_ALLOW_NEW_TAGS = androidx.datastore.preferences.core.booleanPreferencesKey("allow_new_ai_tags")
    }

    val modelIdFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_MODEL_ID] ?: DEFAULT_MODEL_ID
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
