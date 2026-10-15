package org.eu.angsumanray.recipecatcher.ui.review

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.capture.CaptureWorker
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.data.db.dao.TagDao
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import javax.inject.Inject

data class ReviewUiState(
    val isLoading: Boolean = true,
    val isExtracting: Boolean = false,
    val extractionStage: String = "",
    val isFailed: Boolean = false,
    val errorMessage: String? = null,
    val recipeId: Long = 0,
    val title: String = "",
    val description: String = "",
    val servings: Int = 4,
    val prepMin: Int = 0,
    val cookMin: Int = 0,
    val totalMin: Int = 0,
    val cuisine: String = "",
    // Empty means unknown; it is saved as null rather than guessed.
    val complexity: String = "",
    val complexityReason: String = "",
    val spiceLevel: String = "",
    val dietType: String = "",
    val kcalPerServing: Int = 0,
    val makeAhead: Boolean = false,
    val freezerFriendly: Boolean = false,
    val personalNote: String = "",
    val captionRaw: String = "",
    val imagePath: String? = null,
    val ingredients: List<IngredientEntity> = emptyList(),
    val steps: List<StepEntity> = emptyList(),
    val allTags: List<TagEntity> = emptyList(),
    val allDiet: List<TagEntity> = emptyList(),
    val selectedTagIds: Set<Long> = emptySet(),
    val isSaved: Boolean = false
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val tagDao: TagDao,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // Secondary constructor for testing
    constructor(
        recipeRepository: RecipeRepository,
        tagDao: TagDao,
        savedStateHandle: SavedStateHandle
    ) : this(
        recipeRepository = recipeRepository,
        tagDao = tagDao,
        savedStateHandle = savedStateHandle,
        context = android.app.Application()
    )

    private val recipeId: Long = checkNotNull(savedStateHandle["recipeId"])

    private val _uiState = MutableStateFlow(ReviewUiState(recipeId = recipeId))
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    private var hasPopulatedForm: Boolean = false
    private var wasExtracting: Boolean = false

    init {
        observeRecipeFlow()
    }

    private fun observeRecipeFlow() {
        viewModelScope.launch {
            recipeRepository.observeRecipe(recipeId).collect { recipe ->
                if (recipe == null) return@collect
                handleRecipeUpdate(recipe)
            }
        }
    }

    private suspend fun handleRecipeUpdate(recipe: RecipeEntity) {
        val status = recipe.status
        if (status == RecipeEntity.STATUS_PENDING_FETCH || status == RecipeEntity.STATUS_PENDING_EXTRACT) {
            wasExtracting = true
            val stage = if (status == RecipeEntity.STATUS_PENDING_FETCH) {
                when (recipe.sourceType) {
                    SourceType.WEB.value -> "Fetching recipe details from website..."
                    SourceType.YOUTUBE.value -> "Fetching video details from YouTube..."
                    else -> "Fetching recipe details from Instagram..."
                }
            } else {
                "Claude is analyzing ingredients and instructions..."
            }
            _uiState.update { current ->
                current.copy(
                    isLoading = false,
                    isExtracting = true,
                    extractionStage = stage,
                    isFailed = false,
                    errorMessage = null,
                    captionRaw = recipe.captionRaw.orEmpty(),
                    imagePath = recipe.imagePath ?: recipe.coverPhotoPath ?: current.imagePath
                )
            }
        } else if (isFailureStatus(status)) {
            wasExtracting = false
            _uiState.update { current ->
                current.copy(
                    isLoading = false,
                    isExtracting = false,
                    isFailed = true,
                    errorMessage = recipe.errorMessage ?: "Failed to extract recipe"
                )
            }
        } else if (status == RecipeEntity.STATUS_NEEDS_REVIEW || status == RecipeEntity.STATUS_SAVED) {
            if (!hasPopulatedForm || wasExtracting) {
                wasExtracting = false
                hasPopulatedForm = true
                populateFormData(recipe)
            } else {
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        isExtracting = false,
                        isFailed = false,
                        isSaved = status == RecipeEntity.STATUS_SAVED,
                        imagePath = current.imagePath ?: recipe.imagePath ?: recipe.coverPhotoPath
                    )
                }
            }
        } else {
            if (!hasPopulatedForm) {
                hasPopulatedForm = true
                populateFormData(recipe)
            }
        }
    }

    private fun isFailureStatus(status: String): Boolean {
        return status == "failed" ||
            status == RecipeEntity.STATUS_FAILED_FETCH ||
            status == RecipeEntity.STATUS_FAILED_EXTRACT ||
            status == RecipeEntity.STATUS_NO_RECIPE_FOUND ||
            status.startsWith("failed")
    }

    private suspend fun populateFormData(recipe: RecipeEntity) {
        val ingredients = recipeRepository.observeIngredients(recipeId).first()
        val steps = recipeRepository.observeSteps(recipeId).first()
        val selectedTags = recipeRepository.observeTags(recipeId).first()
        val allTags = if (_uiState.value.allTags.isEmpty()) tagDao.getByKind(TagEntity.KIND_TAG) else _uiState.value.allTags
        val allDiet = if (_uiState.value.allDiet.isEmpty()) tagDao.getByKind(TagEntity.KIND_DIET) else _uiState.value.allDiet

        _uiState.update { current ->
            current.copy(
                isLoading = false,
                isExtracting = false,
                isFailed = false,
                errorMessage = null,
                extractionStage = "",
                recipeId = recipeId,
                title = recipe.title.orEmpty(),
                description = recipe.description.orEmpty(),
                servings = recipe.servings ?: 4,
                prepMin = recipe.prepMin ?: 0,
                cookMin = recipe.cookMin ?: 0,
                totalMin = recipe.totalMin ?: 0,
                cuisine = recipe.cuisine.orEmpty(),
                complexity = recipe.complexity.orEmpty(),
                complexityReason = recipe.complexityReason.orEmpty(),
                spiceLevel = recipe.spiceLevel.orEmpty(),
                dietType = recipe.dietType.orEmpty(),
                kcalPerServing = recipe.kcalPerServing ?: 0,
                makeAhead = recipe.makeAhead,
                freezerFriendly = recipe.freezerFriendly,
                personalNote = recipe.personalNote.orEmpty(),
                captionRaw = recipe.captionRaw.orEmpty(),
                imagePath = recipe.imagePath ?: recipe.coverPhotoPath,
                ingredients = ingredients,
                steps = steps,
                allTags = allTags,
                allDiet = allDiet,
                selectedTagIds = selectedTags.map { it.id }.toSet(),
                isSaved = recipe.status == RecipeEntity.STATUS_SAVED
            )
        }
    }

    fun retryCapture() {
        viewModelScope.launch {
            val recipe = recipeRepository.getRecipe(recipeId) ?: return@launch
            val nextStatus = if (recipe.captionRaw.isNullOrBlank()) {
                RecipeEntity.STATUS_PENDING_FETCH
            } else {
                RecipeEntity.STATUS_PENDING_EXTRACT
            }
            recipeRepository.updateRecipeStatus(recipe.id, nextStatus, null)
            wasExtracting = true
            _uiState.update { current ->
                current.copy(
                    isFailed = false,
                    errorMessage = null,
                    isExtracting = true,
                    extractionStage = if (nextStatus == RecipeEntity.STATUS_PENDING_FETCH) {
                        when (recipe.sourceType) {
                            SourceType.WEB.value -> "Fetching recipe details from website..."
                            SourceType.YOUTUBE.value -> "Fetching video details from YouTube..."
                            else -> "Fetching recipe details from Instagram..."
                        }
                    } else {
                        "Claude is analyzing ingredients and instructions..."
                    }
                )
            }
            try {
                CaptureWorker.enqueue(context, recipe.id)
            } catch (e: Exception) {
                // Ignore work enqueue issues in test environments without WorkManager initialized
            }
        }
    }

    fun dismissFailure() {
        viewModelScope.launch {
            if (!hasPopulatedForm) {
                val recipe = recipeRepository.getRecipe(recipeId)
                if (recipe != null) {
                    populateFormData(recipe)
                } else {
                    val allTags = if (_uiState.value.allTags.isEmpty()) tagDao.getByKind(TagEntity.KIND_TAG) else _uiState.value.allTags
                    val allDiet = if (_uiState.value.allDiet.isEmpty()) tagDao.getByKind(TagEntity.KIND_DIET) else _uiState.value.allDiet
                    _uiState.update { current ->
                        current.copy(
                            allTags = allTags,
                            allDiet = allDiet
                        )
                    }
                    hasPopulatedForm = true
                }
            }
            _uiState.update { current ->
                current.copy(
                    isFailed = false,
                    isExtracting = false,
                    errorMessage = null,
                    isLoading = false
                )
            }
        }
    }

    fun updateTitle(title: String) {
        _uiState.value = _uiState.value.copy(title = title)
    }

    fun updateDescription(description: String) {
        _uiState.value = _uiState.value.copy(description = description)
    }

    fun updateServings(servings: Int) {
        _uiState.value = _uiState.value.copy(servings = servings.coerceIn(1, 50))
    }

    fun updateTimes(prep: Int, cook: Int, total: Int) {
        _uiState.value = _uiState.value.copy(prepMin = prep, cookMin = cook, totalMin = total)
    }

    fun updateComplexity(complexity: String) {
        _uiState.value = _uiState.value.copy(complexity = complexity)
    }

    fun updateSpiceLevel(spiceLevel: String) {
        _uiState.value = _uiState.value.copy(spiceLevel = spiceLevel)
    }

    fun updateDietType(dietType: String) {
        _uiState.value = _uiState.value.copy(dietType = dietType)
    }

    fun toggleMakeAhead(value: Boolean) {
        _uiState.value = _uiState.value.copy(makeAhead = value)
    }

    fun toggleFreezerFriendly(value: Boolean) {
        _uiState.value = _uiState.value.copy(freezerFriendly = value)
    }

    fun toggleTag(tagId: Long) {
        val current = _uiState.value.selectedTagIds.toMutableSet()
        if (current.contains(tagId)) current.remove(tagId) else current.add(tagId)
        _uiState.value = _uiState.value.copy(selectedTagIds = current)
    }

    fun updateIngredient(index: Int, name: String, quantity: Double?, unit: String?) {
        val current = _uiState.value.ingredients.toMutableList()
        if (index in current.indices) {
            current[index] = current[index].copy(name = name, quantity = quantity, unit = unit)
            _uiState.value = _uiState.value.copy(ingredients = current)
        }
    }

    fun updateStep(index: Int, text: String) {
        val current = _uiState.value.steps.toMutableList()
        if (index in current.indices) {
            current[index] = current[index].copy(text = text)
            _uiState.value = _uiState.value.copy(steps = current)
        }
    }

    fun saveRecipe(onSuccess: () -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            val existing = recipeRepository.getRecipe(recipeId) ?: return@launch

            val updatedRecipe = existing.copy(
                title = state.title.trim().ifEmpty { "Untitled Recipe" },
                description = state.description.trim().takeIf { it.isNotEmpty() },
                servings = state.servings,
                prepMin = state.prepMin.takeIf { it > 0 },
                cookMin = state.cookMin.takeIf { it > 0 },
                totalMin = (if (state.totalMin > 0) state.totalMin else state.prepMin + state.cookMin).takeIf { it > 0 },
                cuisine = state.cuisine.trim().takeIf { it.isNotEmpty() },
                complexity = state.complexity.takeIf { it.isNotEmpty() },
                complexityReason = state.complexityReason.trim().takeIf { it.isNotEmpty() },
                spiceLevel = state.spiceLevel.takeIf { it.isNotEmpty() },
                dietType = state.dietType.takeIf { it.isNotEmpty() },
                kcalPerServing = state.kcalPerServing.takeIf { it > 0 },
                makeAhead = state.makeAhead,
                freezerFriendly = state.freezerFriendly,
                personalNote = state.personalNote.trim().takeIf { it.isNotEmpty() },
                status = RecipeEntity.STATUS_SAVED,
                savedAt = existing.savedAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            recipeRepository.saveFullRecipe(
                recipe = updatedRecipe,
                ingredients = state.ingredients,
                steps = state.steps,
                tagIds = state.selectedTagIds.toList()
            )

            _uiState.value = _uiState.value.copy(isSaved = true)
            onSuccess()
        }
    }
}
