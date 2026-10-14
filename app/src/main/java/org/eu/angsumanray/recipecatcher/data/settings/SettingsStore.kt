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
    }

    val modelIdFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_MODEL_ID] ?: DEFAULT_MODEL_ID
    }

    val themeFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_THEME] ?: THEME_DARK
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
        webSearches: Int = 0,
        priceInputPerM: Double = DEFAULT_INPUT_PRICE_PER_M,
        priceOutputPerM: Double = DEFAULT_OUTPUT_PRICE_PER_M,
        priceCachePerM: Double = DEFAULT_CACHE_READ_PRICE_PER_M,
        priceWebSearch: Double = DEFAULT_WEB_SEARCH_PRICE
    ): Double {
        val inputCost = (inputTokens.toDouble() / 1_000_000.0) * priceInputPerM
        val outputCost = (outputTokens.toDouble() / 1_000_000.0) * priceOutputPerM
        val cacheCost = (cacheReadTokens.toDouble() / 1_000_000.0) * priceCachePerM
        val webCost = webSearches * priceWebSearch
        return inputCost + outputCost + cacheCost + webCost
    }
}
