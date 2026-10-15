package org.eu.angsumanray.recipecatcher.ui.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eu.angsumanray.recipecatcher.capture.CaptureWorker
import org.eu.angsumanray.recipecatcher.core.capture.UrlNormalizer
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.core.web.WebRecipeFetcher
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.repo.RecipeFilter
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import org.eu.angsumanray.recipecatcher.data.repo.RecipeSortOrder
import org.eu.angsumanray.recipecatcher.data.repo.TagRepository
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeTagEntity
import javax.inject.Inject

enum class LibraryViewMode {
    ALL,
    BY_TAG
}

data class TagGroup(
    val tagId: Long?,
    val tagName: String,
    val recipes: List<RecipeEntity>
)

data class LibraryUiState(
    val filter: RecipeFilter = RecipeFilter(),
    val isPendingExpanded: Boolean = true,
    val viewMode: LibraryViewMode = LibraryViewMode.ALL,
    val allTags: List<TagEntity> = emptyList(),
    val allDiet: List<TagEntity> = emptyList(),
    val allEquipment: List<TagEntity> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val tagRepository: TagRepository,
    private val settingsStore: org.eu.angsumanray.recipecatcher.data.settings.SettingsStore,
    private val webRecipeFetcher: WebRecipeFetcher,
    @ApplicationContext private val context: Context,
    private val commentFetcher: org.eu.angsumanray.recipecatcher.capture.CommentFetcher? = null,
    private val captureLauncher: org.eu.angsumanray.recipecatcher.capture.CaptureLauncher? = null
) : ViewModel() {

    val newRecipesSinceBackup: StateFlow<Int> = settingsStore.newRecipesSinceBackupFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val backupReminderThreshold: StateFlow<Int> = settingsStore.backupReminderThresholdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 10)

    private val _filter = MutableStateFlow(RecipeFilter())
    val filter: StateFlow<RecipeFilter> = _filter.asStateFlow()

    private val _isPendingExpanded = MutableStateFlow(true)
    val isPendingExpanded: StateFlow<Boolean> = _isPendingExpanded.asStateFlow()

    val pendingRecipes: StateFlow<List<RecipeEntity>> = recipeRepository.observePendingRecipes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedRecipes: StateFlow<List<RecipeEntity>> = _filter
        .flatMapLatest { recipeFilter ->
            recipeRepository.observeFilteredRecipes(recipeFilter)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTags: StateFlow<List<TagEntity>> = tagRepository.observeTagsByKind(TagEntity.KIND_TAG)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allDiet: StateFlow<List<TagEntity>> = tagRepository.observeTagsByKind(TagEntity.KIND_DIET)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allEquipment: StateFlow<List<TagEntity>> = tagRepository.observeTagsByKind(TagEntity.KIND_EQUIPMENT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _viewMode = MutableStateFlow(LibraryViewMode.ALL)
    val viewMode: StateFlow<LibraryViewMode> = _viewMode.asStateFlow()

    fun setViewMode(mode: LibraryViewMode) {
        _viewMode.value = mode
    }

    val allRecipeTags: StateFlow<List<RecipeTagEntity>> = recipeRepository.observeAllRecipeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tagGroups: StateFlow<List<TagGroup>> = combine(
        savedRecipes,
        allTags,
        allRecipeTags
    ) { recipes, tags, links ->
        if (recipes.isEmpty()) return@combine emptyList()
        val recipeIdsByTagId = links.groupBy({ it.tagId }, { it.recipeId })
        val recipeMap = recipes.associateBy { it.id }

        val groups = mutableListOf<TagGroup>()
        val taggedRecipeIds = mutableSetOf<Long>()

        for (tag in tags) {
            val recipeIds = recipeIdsByTagId[tag.id]?.distinct().orEmpty()
            val matchingRecipes = recipeIds.mapNotNull { recipeMap[it] }
            if (matchingRecipes.isNotEmpty()) {
                groups.add(TagGroup(tagId = tag.id, tagName = tag.name, recipes = matchingRecipes))
                taggedRecipeIds.addAll(recipeIds)
            }
        }

        val untagged = recipes.filter { it.id !in taggedRecipeIds }
        if (untagged.isNotEmpty()) {
            groups.add(TagGroup(tagId = null, tagName = "Untagged", recipes = untagged))
        }

        groups
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearchQuery(query: String) {
        _filter.value = _filter.value.copy(query = query)
    }

    fun setSortOrder(order: RecipeSortOrder) {
        _filter.value = _filter.value.copy(sortOrder = order)
    }

    fun toggleTagFilter(tagId: Long) {
        val current = _filter.value.tagIds.toMutableSet()
        if (current.contains(tagId)) current.remove(tagId) else current.add(tagId)
        _filter.value = _filter.value.copy(tagIds = current)
    }

    fun setDietTypeFilter(dietType: String?) {
        val current = _filter.value.dietType
        val newDiet = if (current.equals(dietType, ignoreCase = true)) null else dietType
        _filter.value = _filter.value.copy(dietType = newDiet)
    }

    fun toggleWithoutDietFilter(dietTagId: Long) {
        val current = _filter.value.withoutDietTagIds.toMutableSet()
        if (current.contains(dietTagId)) current.remove(dietTagId) else current.add(dietTagId)
        _filter.value = _filter.value.copy(withoutDietTagIds = current)
    }

    fun toggleFavouriteFilter() {
        val current = _filter.value.isFavourite
        _filter.value = _filter.value.copy(isFavourite = if (current == true) null else true)
    }

    fun toggleNeverCookedFilter() {
        _filter.value = _filter.value.copy(neverCooked = !_filter.value.neverCooked)
    }

    fun setComplexityFilter(complexity: String?) {
        val current = _filter.value.complexity
        val newComp = if (current.equals(complexity, ignoreCase = true)) null else complexity
        _filter.value = _filter.value.copy(complexity = newComp)
    }

    fun toggleEquipmentFilter(equipmentTagId: Long) {
        val current = _filter.value.equipmentTagIds.toMutableSet()
        if (current.contains(equipmentTagId)) current.remove(equipmentTagId) else current.add(equipmentTagId)
        _filter.value = _filter.value.copy(equipmentTagIds = current)
    }

    fun setSpiceLevelFilter(spiceLevel: String?) {
        val current = _filter.value.spiceLevel
        val newSpice = if (current.equals(spiceLevel, ignoreCase = true)) null else spiceLevel
        _filter.value = _filter.value.copy(spiceLevel = newSpice)
    }

    fun toggleMakeAheadFilter() {
        val current = _filter.value.makeAhead
        _filter.value = _filter.value.copy(makeAhead = if (current == true) null else true)
    }

    fun toggleFreezerFriendlyFilter() {
        val current = _filter.value.freezerFriendly
        _filter.value = _filter.value.copy(freezerFriendly = if (current == true) null else true)
    }

    fun toggleToReviewFilter() {
        val current = _filter.value.toReview
        _filter.value = _filter.value.copy(toReview = if (current == true) null else true)
    }

    fun clearAllFilters() {
        _filter.value = RecipeFilter()
    }

    fun togglePendingExpanded() {
        _isPendingExpanded.value = !_isPendingExpanded.value
    }

    fun toggleRecipeFavourite(recipe: RecipeEntity) {
        viewModelScope.launch {
            recipeRepository.toggleFavourite(recipe.id, !recipe.isFavourite)
        }
    }

    fun retryCapture(recipe: RecipeEntity) {
        if (captureLauncher != null) {
            captureLauncher.retry(recipe.id)
            return
        }
        viewModelScope.launch {
            recipeRepository.updateRecipeStatus(recipe.id, RecipeEntity.STATUS_PENDING_FETCH, null)
            CaptureWorker.enqueue(context, recipe.id)
        }
    }

    fun pastePageText(recipeId: Long, text: String) {
        viewModelScope.launch {
            val recipe = recipeRepository.getRecipe(recipeId) ?: return@launch
            val trimmed = text.trim()

            // A pasted post or comment link is read for its comments; the link itself is never sent to Claude.
            if (commentFetcher != null && UrlNormalizer.isInstagramPostLink(trimmed)) {
                val previousStatus = recipe.status
                recipeRepository.updateRecipeStatus(recipe.id, RecipeEntity.STATUS_PENDING_FETCH, null)
                val comment = try {
                    commentFetcher.fetchTopComment(trimmed, recipe.captionRaw, userRequested = true)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        recipeRepository.updateRecipeStatus(recipe.id, previousStatus, recipe.errorMessage)
                    }
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (!comment.isNullOrBlank()) {
                    recipeRepository.updateCommentRaw(recipe.id, comment)
                    recipeRepository.updateRecipeStatus(recipe.id, RecipeEntity.STATUS_PENDING_EXTRACT, null)
                    CaptureWorker.enqueue(context, recipe.id)
                } else {
                    recipeRepository.updateRecipeStatus(recipe.id, previousStatus, commentFetcher.linkFailureMessage())
                }
                return@launch
            }

            // Pasted page source goes through the same JSON-LD / readable-text pass as a fetch.
            val sourceText = if (recipe.sourceType == SourceType.WEB.value && HTML_MARKERS.containsMatchIn(text)) {
                withContext(Dispatchers.Default) {
                    webRecipeFetcher.parseHtml(text, recipe.sourceUrl ?: recipe.sourceKey)
                }?.sourceText ?: text
            } else {
                text
            }
            val updated = recipe.copy(
                captionRaw = sourceText,
                status = RecipeEntity.STATUS_PENDING_EXTRACT,
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
            recipeRepository.updateRecipe(updated)
            CaptureWorker.enqueue(context, recipeId)
        }
    }

    fun deleteRecipe(recipeId: Long) {
        viewModelScope.launch {
            // Stop an in-flight capture so it can't write a thumbnail or extraction for the deleted row.
            WorkManager.getInstance(context).cancelUniqueWork("capture_recipe_$recipeId")
            recipeRepository.deleteRecipe(recipeId)
        }
    }

    private companion object {
        val HTML_MARKERS = Regex("<(html|head|body|script|div|article)[\\s>]", RegexOption.IGNORE_CASE)
    }
}
