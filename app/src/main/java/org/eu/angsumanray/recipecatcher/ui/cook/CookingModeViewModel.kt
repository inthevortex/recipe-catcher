package org.eu.angsumanray.recipecatcher.ui.cook

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val remainingMillis: Long,
    val totalSeconds: Int,
    val isRunning: Boolean
) {
    val remainingSeconds: Int
        get() = ((remainingMillis + 999) / 1000).toInt()
}

data class CookingModeUiState(
    val isLoading: Boolean = true,
    val recipe: RecipeEntity? = null,
    val steps: List<StepEntity> = emptyList(),
    val rawIngredients: List<IngredientEntity> = emptyList(),
    /** [rawIngredients] scaled from the recipe's servings to [servings]. */
    val ingredients: List<IngredientEntity> = emptyList(),
    val baseServings: Int = 4,
    val servings: Int = 4,
    val currentStepIndex: Int = 0,
    val checkedIngredientIds: Set<Long> = emptySet(),
    val timers: Map<Int, TimerState> = emptyMap(),
    val isMarkedCooked: Boolean = false,
    val isSaving: Boolean = false
)

@HiltViewModel
class CookingModeViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val cookLogRepository: CookLogRepository,
    private val imageStorageManager: ImageStorageManager,
    private val timerAlerts: CookingTimerAlerts,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val recipeId: Long = checkNotNull(savedStateHandle["recipeId"])

    /** Servings chosen in the viewer; -1 or absent means the recipe's own servings. */
    private val requestedServings: Int? = savedStateHandle.get<Int>("servings")?.takeIf { it > 0 }

    internal var elapsedRealtime: () -> Long = SystemClock::elapsedRealtime

    private val _uiState = MutableStateFlow(CookingModeUiState())
    val uiState: StateFlow<CookingModeUiState> = _uiState.asStateFlow()

    private val timerJobs = mutableMapOf<Int, Job>()
    private val timerEndsAt = mutableMapOf<Int, Long>()

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
                        remainingMillis = seconds * 1000L,
                        totalSeconds = seconds,
                        isRunning = false
                    )
                }
            }.toMap()

            val baseServings = recipe?.servings?.takeIf { it > 0 } ?: 4
            val servings = (requestedServings ?: baseServings).coerceIn(1, 50)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                recipe = recipe,
                baseServings = baseServings,
                servings = servings,
                steps = steps,
                rawIngredients = ingredients,
                ingredients = scaleIngredients(ingredients, baseServings, servings),
                timers = initialTimers
            )

            recipeRepository.observeSteps(recipeId).onEach { updatedSteps ->
                _uiState.value = _uiState.value.copy(steps = updatedSteps)
            }.launchIn(this)

            recipeRepository.observeIngredients(recipeId).onEach { updatedIngredients ->
                val state = _uiState.value
                _uiState.value = state.copy(
                    rawIngredients = updatedIngredients,
                    ingredients = scaleIngredients(updatedIngredients, state.baseServings, state.servings)
                )
            }.launchIn(this)
        }
    }

    private fun scaleIngredients(
        ingredients: List<IngredientEntity>,
        baseServings: Int,
        targetServings: Int
    ): List<IngredientEntity> {
        val factor = QuantityScaler.calculateFactor(baseServings, targetServings)
        return ingredients.map { ing ->
            val (scaledMin, scaledMax) = QuantityScaler.scaleRange(ing.quantity, ing.quantityMax, factor)
            ing.copy(quantity = scaledMin, quantityMax = scaledMax)
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
        val timer = _uiState.value.timers[stepIndex] ?: return
        if (timer.isRunning || timer.remainingMillis <= 0) return

        val endsAt = elapsedRealtime() + timer.remainingMillis
        timerEndsAt[stepIndex] = endsAt
        updateTimer(stepIndex) { it.copy(isRunning = true) }

        timerJobs[stepIndex]?.cancel()
        timerJobs[stepIndex] = viewModelScope.launch {
            while (true) {
                val left = endsAt - elapsedRealtime()
                if (left <= 0) {
                    timerEndsAt.remove(stepIndex)
                    updateTimer(stepIndex) { it.copy(remainingMillis = 0, isRunning = false) }
                    timerAlerts.timerFinished(_uiState.value.recipe?.title, stepIndex + 1)
                    break
                }
                updateTimer(stepIndex) { it.copy(remainingMillis = left) }
                // Wake when the displayed second changes rather than on a fixed tick, so it never drifts.
                delay((left - 1) % 1000 + 1)
            }
        }
    }

    fun pauseTimer(stepIndex: Int) {
        timerJobs.remove(stepIndex)?.cancel()
        val endsAt = timerEndsAt.remove(stepIndex)
        updateTimer(stepIndex) { timer ->
            val left = endsAt?.let { (it - elapsedRealtime()).coerceAtLeast(0) } ?: timer.remainingMillis
            timer.copy(remainingMillis = left, isRunning = false)
        }
    }

    fun resetTimer(stepIndex: Int) {
        timerJobs.remove(stepIndex)?.cancel()
        timerEndsAt.remove(stepIndex)
        updateTimer(stepIndex) { it.copy(remainingMillis = it.totalSeconds * 1000L, isRunning = false) }
    }

    private fun updateTimer(stepIndex: Int, transform: (TimerState) -> TimerState) {
        val timers = _uiState.value.timers
        val timer = timers[stepIndex] ?: return
        _uiState.value = _uiState.value.copy(timers = timers + (stepIndex to transform(timer)))
    }

    fun markAsCooked(
        date: LocalDate = LocalDate.now(),
        note: String? = null,
        photoUri: Uri? = null,
        onComplete: () -> Unit
    ) {
        if (_uiState.value.isSaving) return
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            try {
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
            } finally {
                _uiState.value = _uiState.value.copy(isSaving = false)
            }
        }
    }

    fun createTempCameraUri(): Pair<Uri, java.io.File> {
        return imageStorageManager.createTempCameraUri()
    }

    override fun onCleared() {
        super.onCleared()
        timerJobs.values.forEach { it.cancel() }
        timerJobs.clear()
        timerEndsAt.clear()
    }
}
