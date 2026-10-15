package org.eu.angsumanray.recipecatcher.ui.importer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eu.angsumanray.recipecatcher.capture.ImportWorker
import org.eu.angsumanray.recipecatcher.core.capture.ImportedPost
import org.eu.angsumanray.recipecatcher.core.capture.SavedPostsImporter
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.ImportItemEntity
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.io.InputStream
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

    val progressState: StateFlow<ImportProgressState> = combine(
        countsFlow,
        statusFlow
    ) { counts, status ->
        val queued = counts[0]
        val done = counts[1]
        val skipped = counts[2]
        val failed = counts[3]
        val (paused, cooldownUntil, skippedItems) = status

        val now = System.currentTimeMillis()
        val underCooldown = now < cooldownUntil
        val cooldownSeconds = if (underCooldown) (cooldownUntil - now) / 1000L else 0L
        val estMinutes = (queued * 30 + 59) / 60

        ImportProgressState(
            queuedCount = queued,
            doneCount = done,
            skippedCount = skipped,
            failedCount = failed,
            isPaused = paused,
            isUnderCooldown = underCooldown,
            cooldownRemainingSeconds = cooldownSeconds,
            estimatedFinishMinutes = estMinutes,
            skippedAndFailedItems = skippedItems
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ImportProgressState())

    fun parseStream(inputStream: InputStream, isZip: Boolean) {
        viewModelScope.launch {
            _isParsing.value = true
            _parseError.value = null
            try {
                val posts = withContext(Dispatchers.IO) {
                    if (isZip) {
                        SavedPostsImporter.parseZip(inputStream)
                    } else {
                        val text = inputStream.bufferedReader().use { it.readText() }
                        SavedPostsImporter.parse(text)
                    }
                }
                preparePreview(posts)
            } catch (e: Exception) {
                _parseError.value = "Failed to parse file: ${e.message}"
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
            val entities = posts.map { post ->
                ImportItemEntity(
                    url = post.url,
                    sourceKey = post.shortcode,
                    collection = post.collection,
                    status = ImportItemEntity.STATUS_QUEUED,
                    createdAt = System.currentTimeMillis()
                )
            }
            database.importItemDao().insertAll(entities)
            settingsStore.setImportPaused(false)
            withContext(Dispatchers.Main) {
                _preview.value = null
                ImportWorker.enqueue(context)
            }
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
            settingsStore.setImportPaused(false)
            ImportWorker.enqueue(context)
        }
    }

    fun retryItem(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            database.importItemDao().retry(id)
            settingsStore.setImportPaused(false)
            withContext(Dispatchers.Main) {
                ImportWorker.enqueue(context)
            }
        }
    }

    fun retryAllSkippedAndFailed() {
        viewModelScope.launch(Dispatchers.IO) {
            database.importItemDao().retryAllSkippedAndFailed()
            settingsStore.setImportPaused(false)
            withContext(Dispatchers.Main) {
                ImportWorker.enqueue(context)
            }
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
}
