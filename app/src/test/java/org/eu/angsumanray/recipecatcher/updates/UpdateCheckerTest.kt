package org.eu.angsumanray.recipecatcher.updates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.eu.angsumanray.recipecatcher.data.settings.SecretStore
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import org.eu.angsumanray.recipecatcher.wear.WearSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckerTest {

    private lateinit var context: Context
    private lateinit var settingsStore: SettingsStore
    private var simulatedResponseCode = 200
    private var simulatedResponseBody = "{}"

    private val testOkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(simulatedResponseCode)
                    .message(if (simulatedResponseCode == 200) "OK" else "Error")
                    .body(simulatedResponseBody.toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }
            .build()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val secretStore = SecretStore(context)
        settingsStore = SettingsStore(context, secretStore)
    }

    @Test
    fun `isVersionNewer correctly compares semantic versions`() {
        assertTrue(UpdateChecker.isVersionNewer("v1.2.0", "1.1.0"))
        assertTrue(UpdateChecker.isVersionNewer("1.2.1", "1.2.0"))
        assertTrue(UpdateChecker.isVersionNewer("2.0.0", "1.9.9"))
        assertTrue(UpdateChecker.isVersionNewer("v1.10.0", "v1.9.0"))

        assertFalse(UpdateChecker.isVersionNewer("v1.2.0", "1.2.0"))
        assertFalse(UpdateChecker.isVersionNewer("1.2.0", "v1.2.0"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.9", "1.2.0"))
        assertFalse(UpdateChecker.isVersionNewer("1.0.0", "2.0.0"))
    }

    @Test
    fun `parseAndEvaluateRelease correctly extracts assets, notes, and updates cache`() = runBlocking {
        val jsonFixture = javaClass.classLoader?.getResourceAsStream("updates/release_latest.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("Fixture updates/release_latest.json not found")

        val checker = UpdateChecker(testOkHttpClient, settingsStore, null)
        val result = checker.parseAndEvaluateRelease(jsonFixture, currentVersion = "1.1.0")

        assertTrue(result.isUpdateAvailable)
        assertEquals("v1.2.0", result.latestVersion)
        assertEquals("1.2.0 - Polish, Pantry & Companion Enhancements", result.releaseName)
        assertTrue(result.releaseNotes.contains("Dual model selector"))
        assertEquals(
            "https://github.com/angsuman/recipe-catcher/releases/download/v1.2.0/app-release.apk",
            result.appApkUrl
        )
        assertEquals(
            "https://github.com/angsuman/recipe-catcher/releases/download/v1.2.0/wear-release.apk",
            result.wearApkUrl
        )

        // Verify DataStore cache was populated
        assertEquals("v1.2.0", settingsStore.cachedLatestReleaseTagFlow.first())
        assertTrue(settingsStore.lastUpdateCheckAtFlow.first() > 0L)
    }

    @Test
    fun `parseAndEvaluateRelease reports no update when current version is already latest`() = runBlocking {
        val jsonFixture = javaClass.classLoader?.getResourceAsStream("updates/release_latest.json")
            ?.bufferedReader()?.use { it.readText() }!!

        val checker = UpdateChecker(testOkHttpClient, settingsStore, null)
        val result = checker.parseAndEvaluateRelease(jsonFixture, currentVersion = "1.2.0")

        assertFalse(result.isUpdateAvailable)
        assertEquals("v1.2.0", result.latestVersion)
    }

    @Test
    fun `checkForUpdates handles HTTP 403 and 404 errors cleanly`() = runBlocking {
        simulatedResponseCode = 404
        simulatedResponseBody = "{\"message\":\"Not Found\"}"

        val checker = UpdateChecker(testOkHttpClient, settingsStore, null)

        try {
            checker.checkForUpdates(currentVersion = "1.0.0")
            fail("Expected IOException on HTTP 404")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("404") == true)
        }
    }
}
