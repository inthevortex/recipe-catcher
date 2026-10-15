package org.eu.angsumanray.recipecatcher.updates

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eu.angsumanray.recipecatcher.BuildConfig
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import org.eu.angsumanray.recipecatcher.wear.WearSync
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class GitHubAsset(
    val name: String,
    val browser_download_url: String,
    val size: Long = 0L
)

@Serializable
data class GitHubRelease(
    val tag_name: String,
    val name: String? = null,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList()
)

data class UpdateCheckResult(
    val isUpdateAvailable: Boolean,
    val latestVersion: String,
    val releaseName: String,
    val releaseNotes: String,
    val appApkUrl: String? = null,
    val wearApkUrl: String? = null
)

@Singleton
class UpdateChecker @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val settingsStore: SettingsStore,
    private val wearSync: WearSync? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Checks GitHub Releases API for the latest version and compares it to current app version.
     */
    suspend fun checkForUpdates(
        currentVersion: String = BuildConfig.VERSION_NAME,
        endpointUrl: String = LATEST_RELEASE_URL
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpointUrl)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "RecipeCatcher-Android")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val code = response.code
                Log.w(TAG, "GitHub Releases check failed with HTTP $code")
                throw IOException("GitHub Releases check failed with HTTP $code")
            }

            val bodyString = response.body?.string() ?: throw IOException("Empty release response body")
            parseAndEvaluateRelease(bodyString, currentVersion)
        }
    }

    /**
     * Parses the GitHub release JSON and compares against [currentVersion].
     */
    suspend fun parseAndEvaluateRelease(
        jsonString: String,
        currentVersion: String = BuildConfig.VERSION_NAME
    ): UpdateCheckResult {
        val release = json.decodeFromString<GitHubRelease>(jsonString)
        val now = System.currentTimeMillis()

        // Cache the latest checked tag and timestamp
        settingsStore.setLastUpdateCheck(now, release.tag_name)

        val isNewer = isVersionNewer(release.tag_name, currentVersion)
        val appApk = release.assets.firstOrNull { it.name.equals(APP_APK_NAME, ignoreCase = true) }
            ?: release.assets.firstOrNull { it.name.endsWith("-app-release.apk", ignoreCase = true) }
            ?: release.assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true) && !it.name.contains("wear", ignoreCase = true)
            }
        val wearApk = release.assets.firstOrNull { it.name.equals(WEAR_APK_NAME, ignoreCase = true) }
            ?: release.assets.firstOrNull { it.name.endsWith("-wear-release.apk", ignoreCase = true) }

        if (isNewer) {
            // Propagate /update_available to paired watch companion
            try {
                wearSync?.sendUpdateAvailable(release.tag_name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send update message to wear companion", e)
            }
        }

        return UpdateCheckResult(
            isUpdateAvailable = isNewer,
            latestVersion = release.tag_name,
            releaseName = release.name ?: release.tag_name,
            releaseNotes = release.body ?: "",
            appApkUrl = appApk?.browser_download_url,
            wearApkUrl = wearApk?.browser_download_url
        )
    }

    companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/angsuman/recipe-catcher/releases/latest"
        const val APP_APK_NAME = "app-release.apk"
        const val WEAR_APK_NAME = "wear-release.apk"
        private const val TAG = "UpdateChecker"

        /**
         * Compares semantic versions (e.g. "v1.2.0" vs "1.1.0").
         * Returns true if [latest] is strictly newer than [current].
         */
        fun isVersionNewer(latest: String, current: String): Boolean {
            val vLatest = parseVersion(latest)
            val vCurrent = parseVersion(current)

            for (i in 0 until maxOf(vLatest.size, vCurrent.size)) {
                val l = vLatest.getOrElse(i) { 0 }
                val c = vCurrent.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        }

        private fun parseVersion(versionStr: String): List<Int> {
            val sanitized = versionStr.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore("-") // strip prerelease tags like -beta
            return sanitized.split(".").mapNotNull { it.toIntOrNull() }
        }
    }
}
