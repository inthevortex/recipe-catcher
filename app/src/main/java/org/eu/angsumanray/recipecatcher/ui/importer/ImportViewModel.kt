package org.eu.angsumanray.recipecatcher.ui.importer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eu.angsumanray.recipecatcher.capture.ImportWorker
import org.eu.angsumanray.recipecatcher.core.capture.ImportedPost
import org.eu.angsumanray.recipecatcher.core.capture.SavedPostsImporter
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.ImportItemEntity
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.io.IOException
import javax.inject.Inject

data class ImportPreview(
    val totalFound: Int = 0,
    val newCount: Int = 0,
    val alreadySavedCount: Int = 0,
    val candidatePosts: List<ImportedPost> = emptyList(),
    val hasCollections: Boolean = false
)

data class ImportProgressState(
    val queuedCount: Int = 0,
    val doneCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val isPaused: Boolean = false,
    val isUnderCooldown: Boolean = false,
    val cooldownRemainingSeconds: Long = 0L,
    val estimatedFinishMinutes: Int = 0,
    /** Epoch millis the queue should empty at, or null when nothing is queued. */
    val estimatedFinishAtMillis: Long? = null,
    val skippedAndFailedItems: List<ImportItemEntity> = emptyList()
)

@HiltViewModel
class ImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: RecipeDatabase,
    private val settingsStore: SettingsStore
) : ViewModel() {

    private val _preview = MutableStateFlow<ImportPreview?>(null)
    val preview: StateFlow<ImportPreview?> = _preview.asStateFlow()

    private val _isParsing = MutableStateFlow(false)
    val isParsing: StateFlow<Boolean> = _isParsing.asStateFlow()

    private val _parseError = MutableStateFlow<String?>(null)
    val parseError: StateFlow<String?> = _parseError.asStateFlow()

    val tagWithCollections: StateFlow<Boolean> = settingsStore.tagWithCollectionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val countsFlow = combine(
        database.importItemDao().observeQueuedCount(),
        database.importItemDao().observeDoneCount(),
        database.importItemDao().observeSkippedCount(),
        database.importItemDao().observeFailedCount()
    ) { queued, done, skipped, failed ->
        listOf(queued, done, skipped, failed)
    }

    private val statusFlow = combine(
        settingsStore.importPausedFlow,
        settingsStore.importCooldownUntilFlow,
        database.importItemDao().observeSkippedAndFailed()
    ) { paused, cooldownUntil, skippedItems ->
        Triple(paused, cooldownUntil, skippedItems)
    }

    // Re-evaluates the cooldown countdown and finish estimate while the screen is open.
    private val clockFlow = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(CLOCK_TICK_MILLIS)
        }
    }

    val progressState: StateFlow<ImportProgressState> = combine(
        countsFlow,
        statusFlow,
        clockFlow
    ) { counts, status, now ->
        val (paused, cooldownUntil, skippedItems) = status
        computeProgress(
            queued = counts[0],
            done = counts[1],
            skipped = counts[2],
            failed = counts[3],
            paused = paused,
            cooldownUntil = cooldownUntil,
            now = now
        ).copy(skippedAndFailedItems = skippedItems)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ImportProgressState())

    /** Opens [uri] here rather than in the picker callback, so a revoked or missing file shows an error instead of crashing. */
    fun parseUri(uri: Uri) {
        viewModelScope.launch {
            _isParsing.value = true
            _parseError.value = null
            try {
                val posts = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Couldn't open the selected file")
                    SavedPostsImporter.parseStream(stream)
                }
                preparePreview(posts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _parseError.value = "Failed to parse file: ${e.message ?: "unreadable export"}"
            } finally {
                _isParsing.value = false
            }
        }
    }

    fun parseText(text: String) {
        viewModelScope.launch {
            _isParsing.value = true
            _parseError.value = null
            try {
                val posts = withContext(Dispatchers.Default) {
                    SavedPostsImporter.parse(text)
                }
                preparePreview(posts)
            } catch (e: Exception) {
                _parseError.value = "Failed to parse links: ${e.message}"
            } finally {
                _isParsing.value = false
            }
        }
    }

    private suspend fun preparePreview(posts: List<ImportedPost>) {
        if (posts.isEmpty()) {
            _parseError.value = "No Instagram post or reel links found."
            _preview.value = null
            return
        }

        val recipeDao = database.recipeDao()
        val importDao = database.importItemDao()

        val newPosts = mutableListOf<ImportedPost>()
        var alreadySaved = 0

        withContext(Dispatchers.IO) {
            for (p in posts) {
                val inRecipe = recipeDao.getBySourceKey(p.shortcode) != null
                val inImport = importDao.getBySourceKey(p.shortcode) != null
                if (inRecipe || inImport) {
                    alreadySaved++
                } else {
                    newPosts.add(p)
                }
            }
        }

        val hasCollections = posts.any { !it.collection.isNullOrBlank() }
        _preview.value = ImportPreview(
            totalFound = posts.size,
            newCount = newPosts.size,
            alreadySavedCount = alreadySaved,
            candidatePosts = newPosts,
            hasCollections = hasCollections
        )
    }

    fun setTagWithCollections(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setTagWithCollections(enabled)
        }
    }

    fun confirmImport() {
        val prev = _preview.value ?: return
        val posts = prev.candidatePosts
        if (posts.isEmpty()) {
            _preview.value = null
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            // Distinct timestamps keep the queue in export order, since it is taken by created_at.
            val base = System.currentTimeMillis()
            val entities = posts.mapIndexed { index, post ->
                ImportItemEntity(
                    url = post.url,
                    sourceKey = post.shortcode,
                    collection = post.collection,
                    status = ImportItemEntity.STATUS_QUEUED,
                    createdAt = base + index
                )
            }
            database.importItemDao().insertAll(entities)
            withContext(Dispatchers.Main) {
                _preview.value = null
            }
            startQueue()
        }
    }

    /** Unpauses and starts the worker, or explains why the queue can't run yet. */
    private suspend fun startQueue() {
        settingsStore.setImportPaused(false)
        if (settingsStore.getApiKey().isBlank()) {
            _parseError.value = "Add your Anthropic API key in Settings; the queue starts when you tap Resume."
            settingsStore.setImportPaused(true)
            return
        }
        withContext(Dispatchers.Main) {
            ImportWorker.enqueue(context)
        }
    }

    fun dismissPreview() {
        _preview.value = null
        _parseError.value = null
    }

    fun pauseImport() {
        viewModelScope.launch {
            settingsStore.setImportPaused(true)
            ImportWorker.cancel(context)
        }
    }

    fun resumeImport() {
        viewModelScope.launch {
            startQueue()
        }
    }

    fun retryItem(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            database.importItemDao().retry(id)
            startQueue()
        }
    }

    fun retryAllSkippedAndFailed() {
        viewModelScope.launch(Dispatchers.IO) {
            database.importItemDao().retryAllSkippedAndFailed()
            startQueue()
        }
    }

    fun clearCompleted() {
        viewModelScope.launch(Dispatchers.IO) {
            database.importItemDao().clearCompleted()
        }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            database.importItemDao().deleteAll()
            ImportWorker.cancel(context)
        }
    }

    companion object {
        private const val CLOCK_TICK_MILLIS = 15_000L

        /** Counts, cooldown and finish estimate for the progress card; [now] is passed in for tests. */
        fun computeProgress(
            queued: Int,
            done: Int,
            skipped: Int,
            failed: Int,
            paused: Boolean,
            cooldownUntil: Long,
            now: Long,
            intervalMillis: Long = ImportWorker.DEFAULT_INTERVAL_MILLIS
        ): ImportProgressState {
            val cooldownRemaining = (cooldownUntil - now).coerceAtLeast(0L)
            val remainingMillis = cooldownRemaining + queued * intervalMillis
            return ImportProgressState(
                queuedCount = queued,
                doneCount = done,
                skippedCount = skipped,
                failedCount = failed,
                isPaused = paused,
                isUnderCooldown = cooldownRemaining > 0L,
                cooldownRemainingSeconds = (cooldownRemaining + 999L) / 1000L,
                estimatedFinishMinutes = ((remainingMillis + 59_999L) / 60_000L).toInt(),
                estimatedFinishAtMillis = if (queued > 0) now + remainingMillis else null
            )
        }
    }
}
