package org.eu.angsumanray.recipecatcher.ui.cook

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.core.domain.QuantityScaler
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import org.eu.angsumanray.recipecatcher.data.repo.CookLogRepository
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import java.time.LocalDate
import javax.inject.Inject

data class TimerState(
    val remainingSeconds: Int,
    val totalSeconds: Int,
    val isRunning: Boolean
)

data class CookingModeUiState(
    val isLoading: Boolean = true,
    val recipe: RecipeEntity? = null,
    val steps: List<StepEntity> = emptyList(),
    val ingredients: List<IngredientEntity> = emptyList(),
    val servings: Int = 4,
    val currentStepIndex: Int = 0,
    val checkedIngredientIds: Set<Long> = emptySet(),
    val timers: Map<Int, TimerState> = emptyMap(),
    val isMarkedCooked: Boolean = false
)

@HiltViewModel
class CookingModeViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val cookLogRepository: CookLogRepository,
    private val imageStorageManager: ImageStorageManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val recipeId: Long = checkNotNull(savedStateHandle["recipeId"])

    private val _uiState = MutableStateFlow(CookingModeUiState())
    val uiState: StateFlow<CookingModeUiState> = _uiState.asStateFlow()

    private val timerJobs = mutableMapOf<Int, Job>()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val recipe = recipeRepository.getRecipe(recipeId)
            val steps = recipeRepository.getSteps(recipeId)
            val ingredients = recipeRepository.getIngredients(recipeId)

            val initialTimers = steps.mapIndexedNotNull { index, step ->
                step.timerSeconds?.takeIf { it > 0 }?.let { seconds ->
                    index to TimerState(
                        remainingSeconds = seconds,
                        totalSeconds = seconds,
                        isRunning = false
                    )
                }
            }.toMap()

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                recipe = recipe,
                servings = recipe?.servings ?: 4,
                steps = steps,
                ingredients = ingredients,
                timers = initialTimers
            )

            recipeRepository.observeSteps(recipeId).onEach { updatedSteps ->
                _uiState.value = _uiState.value.copy(steps = updatedSteps)
            }.launchIn(this)

            recipeRepository.observeIngredients(recipeId).onEach { updatedIngredients ->
                _uiState.value = _uiState.value.copy(ingredients = updatedIngredients)
            }.launchIn(this)
        }
    }

    fun setStepIndex(index: Int) {
        val total = _uiState.value.steps.size
        if (total > 0) {
            _uiState.value = _uiState.value.copy(currentStepIndex = index.coerceIn(0, total - 1))
        }
    }

    fun nextStep() {
        setStepIndex(_uiState.value.currentStepIndex + 1)
    }

    fun previousStep() {
        setStepIndex(_uiState.value.currentStepIndex - 1)
    }

    fun toggleIngredientCheck(ingredientId: Long) {
        val current = _uiState.value.checkedIngredientIds.toMutableSet()
        if (current.contains(ingredientId)) {
            current.remove(ingredientId)
        } else {
            current.add(ingredientId)
        }
        _uiState.value = _uiState.value.copy(checkedIngredientIds = current)
    }

    fun startTimer(stepIndex: Int) {
        val currentTimers = _uiState.value.timers.toMutableMap()
        val timer = currentTimers[stepIndex] ?: return
        if (timer.isRunning || timer.remainingSeconds <= 0) return

        currentTimers[stepIndex] = timer.copy(isRunning = true)
        _uiState.value = _uiState.value.copy(timers = currentTimers)

        timerJobs[stepIndex]?.cancel()
        timerJobs[stepIndex] = viewModelScope.launch {
            while (true) {
                delay(1000)
                val state = _uiState.value.timers[stepIndex] ?: break
                if (!state.isRunning) break
                val newRemaining = state.remainingSeconds - 1
                if (newRemaining <= 0) {
                    val updated = _uiState.value.timers.toMutableMap()
                    updated[stepIndex] = state.copy(remainingSeconds = 0, isRunning = false)
                    _uiState.value = _uiState.value.copy(timers = updated)
                    break
                } else {
                    val updated = _uiState.value.timers.toMutableMap()
                    updated[stepIndex] = state.copy(remainingSeconds = newRemaining)
                    _uiState.value = _uiState.value.copy(timers = updated)
                }
            }
        }
    }

    fun pauseTimer(stepIndex: Int) {
        timerJobs[stepIndex]?.cancel()
        val currentTimers = _uiState.value.timers.toMutableMap()
        val timer = currentTimers[stepIndex] ?: return
        currentTimers[stepIndex] = timer.copy(isRunning = false)
        _uiState.value = _uiState.value.copy(timers = currentTimers)
    }

    fun resetTimer(stepIndex: Int) {
        timerJobs[stepIndex]?.cancel()
        val currentTimers = _uiState.value.timers.toMutableMap()
        val timer = currentTimers[stepIndex] ?: return
        currentTimers[stepIndex] = timer.copy(
            remainingSeconds = timer.totalSeconds,
            isRunning = false
        )
        _uiState.value = _uiState.value.copy(timers = currentTimers)
    }

    fun markAsCooked(
        date: LocalDate = LocalDate.now(),
        note: String? = null,
        photoUri: Uri? = null,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            val savedPath = if (photoUri != null) {
                imageStorageManager.saveCookedImageFromUri(photoUri)
            } else null

            cookLogRepository.addCookLog(
                recipeId = recipeId,
                cookedOn = date,
                note = note,
                photoPath = savedPath
            )
            _uiState.value = _uiState.value.copy(isMarkedCooked = true)
            onComplete()
        }
    }

    fun createTempCameraUri(): Pair<Uri, java.io.File> {
        return imageStorageManager.createTempCameraUri()
    }

    override fun onCleared() {
        super.onCleared()
        timerJobs.values.forEach { it.cancel() }
        timerJobs.clear()
    }
}
