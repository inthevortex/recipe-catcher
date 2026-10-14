package org.eu.angsumanray.recipecatcher.ui.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.data.db.dao.TagDao
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import javax.inject.Inject

data class ReviewUiState(
    val isLoading: Boolean = true,
    val recipeId: Long = 0,
    val title: String = "",
    val description: String = "",
    val servings: Int = 4,
    val prepMin: Int = 0,
    val cookMin: Int = 0,
    val totalMin: Int = 0,
    val cuisine: String = "",
    val complexity: String = "medium",
    val complexityReason: String = "",
    val spiceLevel: String = "medium",
    val dietType: String = "vegetarian",
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
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val recipeId: Long = checkNotNull(savedStateHandle["recipeId"])

    private val _uiState = MutableStateFlow(ReviewUiState(recipeId = recipeId))
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val recipe = recipeRepository.getRecipe(recipeId) ?: return@launch
            val ingredients = recipeRepository.observeIngredients(recipeId).first()
            val steps = recipeRepository.observeSteps(recipeId).first()
            val selectedTags = recipeRepository.observeTags(recipeId).first()
            val allTags = tagDao.getByKind(TagEntity.KIND_TAG)
            val allDiet = tagDao.getByKind(TagEntity.KIND_DIET)

            _uiState.value = ReviewUiState(
                isLoading = false,
                recipeId = recipeId,
                title = recipe.title.orEmpty(),
                description = recipe.description.orEmpty(),
                servings = recipe.servings ?: 4,
                prepMin = recipe.prepMin ?: 0,
                cookMin = recipe.cookMin ?: 0,
                totalMin = recipe.totalMin ?: 0,
                cuisine = recipe.cuisine.orEmpty(),
                complexity = recipe.complexity ?: "medium",
                complexityReason = recipe.complexityReason.orEmpty(),
                spiceLevel = recipe.spiceLevel ?: "medium",
                dietType = recipe.dietType ?: "vegetarian",
                kcalPerServing = recipe.kcalPerServing ?: 0,
                makeAhead = recipe.makeAhead,
                freezerFriendly = recipe.freezerFriendly,
                personalNote = recipe.personalNote.orEmpty(),
                captionRaw = recipe.captionRaw.orEmpty(),
                imagePath = recipe.imagePath,
                ingredients = ingredients,
                steps = steps,
                allTags = allTags,
                allDiet = allDiet,
                selectedTagIds = selectedTags.map { it.id }.toSet(),
                isSaved = recipe.status == RecipeEntity.STATUS_SAVED
            )
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
                prepMin = state.prepMin,
                cookMin = state.cookMin,
                totalMin = if (state.totalMin > 0) state.totalMin else (state.prepMin + state.cookMin),
                cuisine = state.cuisine.trim().takeIf { it.isNotEmpty() },
                complexity = state.complexity,
                complexityReason = state.complexityReason.trim().takeIf { it.isNotEmpty() },
                spiceLevel = state.spiceLevel,
                dietType = state.dietType,
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
