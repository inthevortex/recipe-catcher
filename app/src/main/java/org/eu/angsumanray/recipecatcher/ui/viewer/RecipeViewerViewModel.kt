package org.eu.angsumanray.recipecatcher.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.core.domain.QuantityScaler
import org.eu.angsumanray.recipecatcher.core.llm.ExtractionInput
import org.eu.angsumanray.recipecatcher.core.llm.NoRecipeFoundException
import org.eu.angsumanray.recipecatcher.core.llm.RecipeExtractor
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.data.db.entity.CookLogEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import org.eu.angsumanray.recipecatcher.data.repo.CookLogRepository
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import org.eu.angsumanray.recipecatcher.data.repo.TagRepository
import org.eu.angsumanray.recipecatcher.data.repo.toExtractionUsageEntity
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.time.LocalDate
import javax.inject.Inject

data class RecipeViewerUiState(
    val isLoading: Boolean = true,
    val recipe: RecipeEntity? = null,
    val rawIngredients: List<IngredientEntity> = emptyList(),
    val scaledIngredients: List<IngredientEntity> = emptyList(),
    val steps: List<StepEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val dietTags: List<TagEntity> = emptyList(),
    val equipmentTags: List<TagEntity> = emptyList(),
    val consumableTags: List<TagEntity> = emptyList(),
    val cookLogs: List<CookLogEntity> = emptyList(),
    val timesCooked: Int = 0,
    val lastCookedDate: String? = null,
    val baseServings: Int = 4,
    val currentServings: Int = 4,
    val checkedIngredientIds: Set<Long> = emptySet(),
    val isReExtracting: Boolean = false,
    val reExtractError: String? = null
)

@HiltViewModel
class RecipeViewerViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val cookLogRepository: CookLogRepository,
    private val tagRepository: TagRepository,
    private val recipeExtractor: RecipeExtractor,
    private val imageStorageManager: ImageStorageManager,
    private val settingsStore: SettingsStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val recipeId: Long = checkNotNull(savedStateHandle["recipeId"])

    private val _uiState = MutableStateFlow(RecipeViewerUiState())
    val uiState: StateFlow<RecipeViewerUiState> = _uiState.asStateFlow()

    init {
        observeData()
    }

    private fun observeData() {
        viewModelScope.launch {
            val recipe = recipeRepository.getRecipe(recipeId)
            val rawIngs = recipeRepository.getIngredients(recipeId)
            val steps = recipeRepository.getSteps(recipeId)
            val allTags = recipeRepository.getTags(recipeId)
            val logs = cookLogRepository.getCookLogs(recipeId)

            val baseServings = recipe?.servings ?: 4
            val current = if (_uiState.value.recipe == null) baseServings else _uiState.value.currentServings
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                recipe = recipe,
                baseServings = baseServings,
                currentServings = current,
                rawIngredients = rawIngs,
                scaledIngredients = scaleIngredients(rawIngs, baseServings, current),
                steps = steps,
                tags = allTags.filter { it.kind == TagEntity.KIND_TAG },
                dietTags = allTags.filter { it.kind == TagEntity.KIND_DIET },
                equipmentTags = allTags.filter { it.kind == TagEntity.KIND_EQUIPMENT },
                consumableTags = allTags.filter { it.kind == TagEntity.KIND_CONSUMABLE },
                cookLogs = logs,
                timesCooked = logs.size,
                lastCookedDate = logs.firstOrNull()?.cookedOn?.toString()
            )

            recipeRepository.observeRecipe(recipeId).onEach { updatedRecipe ->
                if (updatedRecipe != null) {
                    val base = updatedRecipe.servings ?: 4
                    val curr = _uiState.value.currentServings
                    _uiState.value = _uiState.value.copy(
                        recipe = updatedRecipe,
                        baseServings = base,
                        scaledIngredients = scaleIngredients(_uiState.value.rawIngredients, base, curr)
                    )
                }
            }.launchIn(this)

            recipeRepository.observeIngredients(recipeId).onEach { ingredients ->
                val base = _uiState.value.baseServings
                val curr = _uiState.value.currentServings
                _uiState.value = _uiState.value.copy(
                    rawIngredients = ingredients,
                    scaledIngredients = scaleIngredients(ingredients, base, curr)
                )
            }.launchIn(this)

            recipeRepository.observeSteps(recipeId).onEach { updatedSteps ->
                _uiState.value = _uiState.value.copy(steps = updatedSteps)
            }.launchIn(this)

            recipeRepository.observeTags(recipeId).onEach { updatedTags ->
                _uiState.value = _uiState.value.copy(
                    tags = updatedTags.filter { it.kind == TagEntity.KIND_TAG },
                    dietTags = updatedTags.filter { it.kind == TagEntity.KIND_DIET },
                    equipmentTags = updatedTags.filter { it.kind == TagEntity.KIND_EQUIPMENT },
                    consumableTags = updatedTags.filter { it.kind == TagEntity.KIND_CONSUMABLE }
                )
            }.launchIn(this)

            cookLogRepository.observeCookLogs(recipeId).onEach { updatedLogs ->
                _uiState.value = _uiState.value.copy(
                    cookLogs = updatedLogs,
                    timesCooked = updatedLogs.size,
                    lastCookedDate = updatedLogs.firstOrNull()?.cookedOn?.toString()
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

    fun setServings(target: Int) {
        val clamped = target.coerceIn(1, 50)
        val state = _uiState.value
        _uiState.value = state.copy(
            currentServings = clamped,
            scaledIngredients = scaleIngredients(state.rawIngredients, state.baseServings, clamped)
        )
    }

    fun incrementServings() {
        setServings(_uiState.value.currentServings + 1)
    }

    fun decrementServings() {
        setServings(_uiState.value.currentServings - 1)
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

    fun clearIngredientChecks() {
        _uiState.value = _uiState.value.copy(checkedIngredientIds = emptySet())
    }

    fun toggleFavourite() {
        val recipe = _uiState.value.recipe ?: return
        val newFav = !recipe.isFavourite
        viewModelScope.launch {
            recipeRepository.toggleFavourite(recipeId, newFav)
        }
    }

    fun updatePersonalNote(note: String) {
        viewModelScope.launch {
            recipeRepository.updatePersonalNote(recipeId, note.trim().ifEmpty { null })
        }
    }

    fun setAsCoverPhoto(photoPath: String) {
        viewModelScope.launch {
            recipeRepository.updateCoverPhotoPath(recipeId, photoPath)
        }
    }

    fun markAsCooked(
        date: LocalDate,
        note: String?,
        photoUri: Uri?
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
        }
    }

    fun createTempCameraUri(): Pair<Uri, java.io.File> {
        return imageStorageManager.createTempCameraUri()
    }

    fun deleteRecipe(onDeleted: () -> Unit) {
        viewModelScope.launch {
            recipeRepository.deleteRecipe(recipeId)
            onDeleted()
        }
    }

    fun reExtract(onComplete: () -> Unit) {
        val recipe = _uiState.value.recipe ?: return
        val caption = recipe.captionRaw
        if (caption.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(reExtractError = "No raw source caption stored.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isReExtracting = true, reExtractError = null)
            try {
                val tags = tagRepository.getTagsByKind(TagEntity.KIND_TAG).map { it.name }
                val diet = tagRepository.getTagsByKind(TagEntity.KIND_DIET).map { it.name }
                val equipment = tagRepository.getTagsByKind(TagEntity.KIND_EQUIPMENT).map { it.name }
                val consumables = tagRepository.getTagsByKind(TagEntity.KIND_CONSUMABLE).map { it.name }
                val allowNewTags = settingsStore.allowNewAiTagsFlow.first()

                val sourceType = when (recipe.sourceType) {
                    "instagram" -> SourceType.INSTAGRAM
                    "youtube" -> SourceType.YOUTUBE
                    "manual" -> SourceType.MANUAL
                    else -> SourceType.WEB
                }

                val activeModel = settingsStore.modelIdFlow.first()
                val result = recipeExtractor.extract(
                    ExtractionInput(
                        sourceType = sourceType,
                        sourceText = caption,
                        topComment = recipe.commentRaw,
                        author = recipe.authorHandle,
                        allowedTags = tags,
                        allowedDiet = diet,
                        allowedEquipment = equipment,
                        allowedConsumables = consumables,
                        allowNewTags = allowNewTags
                    )
                )

                result.fold(
                    onSuccess = { extracted ->
                        val tagsByKind = tagRepository.getAllTags().groupBy { it.kind }
                        fun tagIds(kind: String, names: List<String>): List<Long> {
                            val byName = tagsByKind[kind].orEmpty().associateBy { it.name.trim().lowercase() }
                            return names.mapNotNull { byName[it.trim().lowercase()]?.id }
                        }
                        val matchedTagIds = tagIds(TagEntity.KIND_TAG, extracted.tags) +
                            tagIds(TagEntity.KIND_DIET, extracted.contains) +
                            tagIds(TagEntity.KIND_EQUIPMENT, extracted.equipment) +
                            tagIds(TagEntity.KIND_CONSUMABLE, extracted.consumables)

                        var ingPos = 0
                        val ingredientEntities = extracted.ingredientGroups.flatMap { group ->
                            group.items.map { item ->
                                IngredientEntity(
                                    recipeId = recipe.id,
                                    position = ingPos++,
                                    groupName = group.name?.ifBlank { null },
                                    quantity = item.quantity?.takeIf { it > 0 },
                                    quantityMax = item.quantityMax?.takeIf { it > 0 },
                                    unit = item.unit?.ifBlank { null },
                                    name = item.name,
                                    baseName = item.baseName,
                                    category = item.category,
                                    note = item.note?.ifBlank { null },
                                    rawText = item.rawText
                                )
                            }
                        }

                        val stepEntities = extracted.steps.mapIndexed { index, step ->
                            StepEntity(
                                recipeId = recipe.id,
                                position = index,
                                text = step.text,
                                timerSeconds = step.timerSeconds?.takeIf { it > 0 }
                            )
                        }

                        val model = extracted.usage.model ?: activeModel
                        recipeRepository.saveReExtraction(
                            recipeId = recipe.id,
                            ingredients = ingredientEntities,
                            steps = stepEntities,
                            aiTagIds = matchedTagIds,
                            usage = extracted.usage.toExtractionUsageEntity(model)
                        ) { current ->
                            current.copy(
                                title = extracted.title?.ifBlank { current.title } ?: current.title,
                                description = extracted.description?.ifBlank { null },
                                servings = extracted.servings?.takeIf { it > 0 } ?: current.servings,
                                prepMin = extracted.prepMinutes?.takeIf { it > 0 },
                                cookMin = extracted.cookMinutes?.takeIf { it > 0 },
                                totalMin = extracted.totalMinutes?.takeIf { it > 0 },
                                cuisine = extracted.cuisine?.ifBlank { null },
                                complexity = extracted.complexity?.ifBlank { null },
                                complexityReason = extracted.complexityReason?.ifBlank { null },
                                spiceLevel = extracted.spiceLevel?.ifBlank { null },
                                dietType = extracted.dietType?.ifBlank { null },
                                kcalPerServing = extracted.kcalPerServing?.takeIf { it > 0 },
                                makeAhead = extracted.makeAhead,
                                freezerFriendly = extracted.freezerFriendly,
                                llmModel = model,
                                status = RecipeEntity.STATUS_SAVED,
                                updatedAt = System.currentTimeMillis()
                            )
                        }

                        _uiState.value = _uiState.value.copy(isReExtracting = false)
                        onComplete()
                    },
                    onFailure = { err ->
                        if (err is NoRecipeFoundException) {
                            err.usage?.let { recipeRepository.logUsage(it.toExtractionUsageEntity(activeModel)) }
                        }
                        _uiState.value = _uiState.value.copy(
                            isReExtracting = false,
                            reExtractError = err.message ?: "Re-extraction failed"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isReExtracting = false,
                    reExtractError = e.message ?: "Re-extraction error"
                )
            }
        }
    }
}
