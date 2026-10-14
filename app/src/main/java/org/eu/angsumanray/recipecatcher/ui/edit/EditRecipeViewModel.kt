package org.eu.angsumanray.recipecatcher.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.core.llm.ExtractionInput
import org.eu.angsumanray.recipecatcher.core.llm.RecipeExtractor
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.data.db.dao.TagDao
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import org.eu.angsumanray.recipecatcher.data.repo.TagRepository
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.util.UUID
import javax.inject.Inject

data class EditRecipeUiState(
    val isLoading: Boolean = true,
    val recipeId: Long = 0,
    val isNewRecipe: Boolean = true,
    val title: String = "",
    val description: String = "",
    val servings: Int = 4,
    val prepMin: Int = 0,
    val cookMin: Int = 0,
    val totalMin: Int = 0,
    val cuisine: String = "",
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
    val coverPhotoPath: String? = null,
    val ingredients: List<IngredientEntity> = emptyList(),
    val steps: List<StepEntity> = emptyList(),
    val allTags: List<TagEntity> = emptyList(),
    val allDiet: List<TagEntity> = emptyList(),
    val allEquipment: List<TagEntity> = emptyList(),
    val allConsumables: List<TagEntity> = emptyList(),
    val selectedTagIds: Set<Long> = emptySet(),
    val isExtractingText: Boolean = false,
    val extractError: String? = null,
    val isSaved: Boolean = false
)

@HiltViewModel
class EditRecipeViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val tagRepository: TagRepository,
    private val recipeExtractor: RecipeExtractor,
    private val settingsStore: SettingsStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val recipeId: Long = savedStateHandle["recipeId"] ?: 0L

    private val _uiState = MutableStateFlow(EditRecipeUiState(recipeId = recipeId, isNewRecipe = recipeId == 0L))
    val uiState: StateFlow<EditRecipeUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val allTags = tagRepository.getTagsByKind(TagEntity.KIND_TAG)
            val allDiet = tagRepository.getTagsByKind(TagEntity.KIND_DIET)
            val allEquipment = tagRepository.getTagsByKind(TagEntity.KIND_EQUIPMENT)
            val allConsumables = tagRepository.getTagsByKind(TagEntity.KIND_CONSUMABLE)

            if (recipeId == 0L) {
                // New manual recipe
                _uiState.value = EditRecipeUiState(
                    isLoading = false,
                    recipeId = 0L,
                    isNewRecipe = true,
                    servings = 4,
                    allTags = allTags,
                    allDiet = allDiet,
                    allEquipment = allEquipment,
                    allConsumables = allConsumables,
                    ingredients = listOf(IngredientEntity(recipeId = 0, position = 0, name = "", baseName = "", category = "pantry", rawText = "")),
                    steps = listOf(StepEntity(recipeId = 0, position = 0, text = ""))
                )
            } else {
                val recipe = recipeRepository.getRecipe(recipeId)
                if (recipe == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    return@launch
                }

                val ingredients = recipeRepository.observeIngredients(recipeId).first()
                val steps = recipeRepository.observeSteps(recipeId).first()
                val selectedTags = recipeRepository.observeTags(recipeId).first()

                _uiState.value = EditRecipeUiState(
                    isLoading = false,
                    recipeId = recipeId,
                    isNewRecipe = false,
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
                    imagePath = recipe.imagePath,
                    coverPhotoPath = recipe.coverPhotoPath,
                    ingredients = if (ingredients.isEmpty()) {
                        listOf(IngredientEntity(recipeId = recipeId, position = 0, name = "", baseName = "", category = "pantry", rawText = ""))
                    } else ingredients,
                    steps = if (steps.isEmpty()) {
                        listOf(StepEntity(recipeId = recipeId, position = 0, text = ""))
                    } else steps,
                    allTags = allTags,
                    allDiet = allDiet,
                    allEquipment = allEquipment,
                    allConsumables = allConsumables,
                    selectedTagIds = selectedTags.map { it.id }.toSet()
                )
            }
        }
    }

    fun updateTitle(title: String) { _uiState.value = _uiState.value.copy(title = title) }
    fun updateDescription(description: String) { _uiState.value = _uiState.value.copy(description = description) }
    fun updateServings(servings: Int) { _uiState.value = _uiState.value.copy(servings = servings.coerceIn(1, 50)) }
    fun updateTimes(prep: Int, cook: Int, total: Int) {
        _uiState.value = _uiState.value.copy(prepMin = prep, cookMin = cook, totalMin = total)
    }
    fun updateCuisine(cuisine: String) { _uiState.value = _uiState.value.copy(cuisine = cuisine) }
    fun updateComplexity(complexity: String, reason: String = _uiState.value.complexityReason) {
        _uiState.value = _uiState.value.copy(complexity = complexity, complexityReason = reason)
    }
    fun updateSpiceLevel(spiceLevel: String) { _uiState.value = _uiState.value.copy(spiceLevel = spiceLevel) }
    fun updateDietType(dietType: String) { _uiState.value = _uiState.value.copy(dietType = dietType) }
    fun updateCalories(kcal: Int) { _uiState.value = _uiState.value.copy(kcalPerServing = kcal) }
    fun toggleMakeAhead(value: Boolean) { _uiState.value = _uiState.value.copy(makeAhead = value) }
    fun toggleFreezerFriendly(value: Boolean) { _uiState.value = _uiState.value.copy(freezerFriendly = value) }
    fun updatePersonalNote(note: String) { _uiState.value = _uiState.value.copy(personalNote = note) }

    fun toggleTag(tagId: Long) {
        val current = _uiState.value.selectedTagIds.toMutableSet()
        if (current.contains(tagId)) current.remove(tagId) else current.add(tagId)
        _uiState.value = _uiState.value.copy(selectedTagIds = current)
    }

    fun addIngredient() {
        val current = _uiState.value.ingredients.toMutableList()
        current.add(IngredientEntity(recipeId = recipeId, position = current.size, name = "", baseName = "", category = "pantry", rawText = ""))
        _uiState.value = _uiState.value.copy(ingredients = current)
    }

    fun updateIngredient(index: Int, name: String, quantity: Double?, unit: String?, note: String?, groupName: String?) {
        val current = _uiState.value.ingredients.toMutableList()
        if (index in current.indices) {
            val prev = current[index]
            current[index] = prev.copy(
                name = name,
                baseName = name.trim().lowercase(),
                quantity = quantity,
                unit = unit?.trim()?.ifEmpty { null },
                note = note?.trim()?.ifEmpty { null },
                groupName = groupName?.trim()?.ifEmpty { null },
                rawText = "${quantity ?: ""} ${unit ?: ""} $name".trim()
            )
            _uiState.value = _uiState.value.copy(ingredients = current)
        }
    }

    fun removeIngredient(index: Int) {
        val current = _uiState.value.ingredients.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _uiState.value = _uiState.value.copy(ingredients = current)
        }
    }

    fun addStep() {
        val current = _uiState.value.steps.toMutableList()
        current.add(StepEntity(recipeId = recipeId, position = current.size, text = ""))
        _uiState.value = _uiState.value.copy(steps = current)
    }

    fun updateStep(index: Int, text: String, timerSeconds: Int?) {
        val current = _uiState.value.steps.toMutableList()
        if (index in current.indices) {
            current[index] = current[index].copy(text = text, timerSeconds = timerSeconds)
            _uiState.value = _uiState.value.copy(steps = current)
        }
    }

    fun removeStep(index: Int) {
        val current = _uiState.value.steps.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _uiState.value = _uiState.value.copy(steps = current)
        }
    }

    fun fillFromPastedText(text: String, onFinished: () -> Unit) {
        if (text.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExtractingText = true, extractError = null)
            try {
                val tags = tagRepository.getTagsByKind(TagEntity.KIND_TAG).map { it.name }
                val diet = tagRepository.getTagsByKind(TagEntity.KIND_DIET).map { it.name }
                val equipment = tagRepository.getTagsByKind(TagEntity.KIND_EQUIPMENT).map { it.name }
                val consumables = tagRepository.getTagsByKind(TagEntity.KIND_CONSUMABLE).map { it.name }
                val allowNewTags = settingsStore.allowNewAiTagsFlow.first()

                val result = recipeExtractor.extract(
                    ExtractionInput(
                        sourceType = SourceType.MANUAL,
                        sourceText = text,
                        topComment = null,
                        author = null,
                        allowedTags = tags,
                        allowedDiet = diet,
                        allowedEquipment = equipment,
                        allowedConsumables = consumables,
                        allowNewTags = allowNewTags
                    )
                )

                result.fold(
                    onSuccess = { extracted ->
                        val allDbTags = tagRepository.getAllTags()
                        val tagIdMap = allDbTags.associate { (it.name.lowercase() to it.kind) to it.id }

                        val matchedTagIds = mutableSetOf<Long>()
                        extracted.tags.forEach { name ->
                            tagIdMap[name.lowercase() to TagEntity.KIND_TAG]?.let { matchedTagIds.add(it) }
                        }
                        extracted.contains.forEach { name ->
                            tagIdMap[name.lowercase() to TagEntity.KIND_DIET]?.let { matchedTagIds.add(it) }
                        }
                        extracted.equipment.forEach { name ->
                            tagIdMap[name.lowercase() to TagEntity.KIND_EQUIPMENT]?.let { matchedTagIds.add(it) }
                        }
                        extracted.consumables.forEach { name ->
                            tagIdMap[name.lowercase() to TagEntity.KIND_CONSUMABLE]?.let { matchedTagIds.add(it) }
                        }

                        var ingPos = 0
                        val ingredientEntities = extracted.ingredientGroups.flatMap { group ->
                            group.items.map { item ->
                                IngredientEntity(
                                    recipeId = recipeId,
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
                                recipeId = recipeId,
                                position = index,
                                text = step.text,
                                timerSeconds = step.timerSeconds?.takeIf { it > 0 }
                            )
                        }

                        _uiState.value = _uiState.value.copy(
                            isExtractingText = false,
                            title = extracted.title?.ifBlank { _uiState.value.title } ?: _uiState.value.title,
                            description = extracted.description?.ifBlank { _uiState.value.description } ?: _uiState.value.description,
                            servings = extracted.servings?.takeIf { it > 0 } ?: _uiState.value.servings,
                            prepMin = extracted.prepMinutes?.takeIf { it > 0 } ?: _uiState.value.prepMin,
                            cookMin = extracted.cookMinutes?.takeIf { it > 0 } ?: _uiState.value.cookMin,
                            totalMin = extracted.totalMinutes?.takeIf { it > 0 } ?: _uiState.value.totalMin,
                            cuisine = extracted.cuisine?.ifBlank { _uiState.value.cuisine } ?: _uiState.value.cuisine,
                            complexity = extracted.complexity?.ifBlank { _uiState.value.complexity } ?: _uiState.value.complexity,
                            complexityReason = extracted.complexityReason?.ifBlank { _uiState.value.complexityReason } ?: _uiState.value.complexityReason,
                            spiceLevel = extracted.spiceLevel?.ifBlank { _uiState.value.spiceLevel } ?: _uiState.value.spiceLevel,
                            dietType = extracted.dietType?.ifBlank { _uiState.value.dietType } ?: _uiState.value.dietType,
                            kcalPerServing = extracted.kcalPerServing?.takeIf { it > 0 } ?: _uiState.value.kcalPerServing,
                            makeAhead = extracted.makeAhead || _uiState.value.makeAhead,
                            freezerFriendly = extracted.freezerFriendly || _uiState.value.freezerFriendly,
                            captionRaw = text,
                            ingredients = if (ingredientEntities.isNotEmpty()) ingredientEntities else _uiState.value.ingredients,
                            steps = if (stepEntities.isNotEmpty()) stepEntities else _uiState.value.steps,
                            selectedTagIds = matchedTagIds
                        )
                        onFinished()
                    },
                    onFailure = { err ->
                        _uiState.value = _uiState.value.copy(
                            isExtractingText = false,
                            extractError = err.message ?: "Failed to extract recipe from pasted text"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isExtractingText = false,
                    extractError = e.message ?: "Error extracting text"
                )
            }
        }
    }

    fun saveRecipe(onSuccess: (Long) -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            val now = System.currentTimeMillis()

            val recipeToSave = if (state.isNewRecipe) {
                RecipeEntity(
                    sourceType = "manual",
                    sourceKey = UUID.randomUUID().toString(),
                    sourceUrl = null,
                    authorHandle = null,
                    captionRaw = state.captionRaw.ifBlank { null },
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
                    reviewed = true,
                    status = RecipeEntity.STATUS_SAVED,
                    createdAt = now,
                    updatedAt = now,
                    savedAt = now
                )
            } else {
                val existing = recipeRepository.getRecipe(recipeId) ?: return@launch
                existing.copy(
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
                    reviewed = true, // Clear "To review" mark on save
                    status = RecipeEntity.STATUS_SAVED,
                    savedAt = existing.savedAt ?: now,
                    updatedAt = now
                )
            }

            val validIngredients = state.ingredients.filter { it.name.isNotBlank() }
            val validSteps = state.steps.filter { it.text.isNotBlank() }

            val savedId = recipeRepository.saveFullRecipe(
                recipe = recipeToSave,
                ingredients = validIngredients,
                steps = validSteps,
                tagIds = state.selectedTagIds.toList()
            )

            _uiState.value = _uiState.value.copy(isSaved = true)
            onSuccess(savedId)
        }
    }

    fun deleteRecipe(onDeleted: () -> Unit) {
        if (!_uiState.value.isNewRecipe) {
            viewModelScope.launch {
                recipeRepository.deleteRecipe(recipeId)
                onDeleted()
            }
        } else {
            onDeleted()
        }
    }
}
