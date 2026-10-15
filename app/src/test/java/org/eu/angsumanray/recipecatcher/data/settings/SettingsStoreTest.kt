package org.eu.angsumanray.recipecatcher.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {

    private lateinit var context: Context
    private lateinit var secretStore: SecretStore
    private lateinit var settingsStore: SettingsStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        secretStore = SecretStore(context)
        settingsStore = SettingsStore(context, secretStore)
    }

    @Test
    fun `SecretStore encrypts and decrypts text`() {
        val original = "sk-ant-api03-test-secret-key-12345"
        val encrypted = secretStore.encrypt(original)
        assertTrue(encrypted.isNotBlank())
        assertTrue(encrypted != original)

        val decrypted = secretStore.decrypt(encrypted)
        assertEquals(original, decrypted)
    }

    @Test
    fun `SettingsStore stores and retrieves API key`() = runBlocking {
        val key = "sk-ant-test-key-abc"
        settingsStore.saveApiKey(key)
        val retrieved = settingsStore.getApiKey()
        assertEquals(key, retrieved)
    }

    @Test
    fun `SettingsStore keeps extraction on Haiku and updates theme`() = runBlocking {
        assertEquals("claude-haiku-5-5", settingsStore.modelIdFlow.first())
        assertEquals("dark", settingsStore.themeFlow.first())


        settingsStore.setTheme(SettingsStore.THEME_LIGHT)
        assertEquals(SettingsStore.THEME_LIGHT, settingsStore.themeFlow.first())
    }

    @Test
    fun `SettingsStore manages chat model ID with dual model support`() = runBlocking {
        // Defaults to claude-haiku-5-5
        assertEquals(SettingsStore.DEFAULT_CHAT_MODEL_ID, settingsStore.chatModelIdFlow.first())

        // Set to Claude Sonnet
        settingsStore.setChatModelId(SettingsStore.CHAT_MODEL_SONNET)
        assertEquals("claude-sonnet-5-5", settingsStore.chatModelIdFlow.first())
        assertEquals("claude-haiku-5-5", settingsStore.modelIdFlow.first())

        // Revert back to Haiku
        settingsStore.setChatModelId(SettingsStore.CHAT_MODEL_HAIKU)
        assertEquals("claude-haiku-5-5", settingsStore.chatModelIdFlow.first())

        // An unknown stored ID falls back to Haiku, matching what Settings shows
        settingsStore.setChatModelId("claude-sonnet-4")
        assertEquals(SettingsStore.DEFAULT_CHAT_MODEL_ID, settingsStore.chatModelIdFlow.first())
    }

    @Test
    fun `SettingsStore persists update check state`() = runBlocking {
        assertEquals(0L, settingsStore.lastUpdateCheckAtFlow.first())
        assertEquals(null, settingsStore.cachedLatestReleaseTagFlow.first())

        val now = 1729000000000L
        settingsStore.setLastUpdateCheck(now, "v1.2.0")
        assertEquals(now, settingsStore.lastUpdateCheckAtFlow.first())
        assertEquals("v1.2.0", settingsStore.cachedLatestReleaseTagFlow.first())
    }

    @Test
    fun `SettingsStore calculates AI spend accurately`() {
        // 2000 input tokens at $0.10/M = $0.0002
        // 800 output tokens at $0.50/M = $0.0004
        // 500 cache read tokens at $0.01/M = $0.000005
        // Total = $0.000605
        val cost = settingsStore.calculateSpend(
            inputTokens = 2000L,
            outputTokens = 800L,
            cacheReadTokens = 500L,
            webSearches = 0
        )
        assertEquals(0.000605, cost, 0.000001)
    }
}
