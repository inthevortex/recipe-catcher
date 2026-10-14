package org.eu.angsumanray.recipecatcher.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.core.llm.ExtractedRecipe
import org.eu.angsumanray.recipecatcher.core.llm.ExtractionInput
import org.eu.angsumanray.recipecatcher.core.llm.LlmUsage
import org.eu.angsumanray.recipecatcher.core.llm.llmErrorMessage
import org.eu.angsumanray.recipecatcher.core.llm.NoRecipeFoundException
import org.eu.angsumanray.recipecatcher.core.llm.RecipeExtractor
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.data.db.dao.TagDao
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.LlmUsageEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeTagEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import org.eu.angsumanray.recipecatcher.data.repo.TagRepository
import org.eu.angsumanray.recipecatcher.data.repo.toExtractionUsageEntity
import org.eu.angsumanray.recipecatcher.core.llm.ChatPlanner
import org.eu.angsumanray.recipecatcher.core.llm.WebRecipeSuggestion
import org.eu.angsumanray.recipecatcher.core.web.WebRecipeFetcher
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.util.UUID
import javax.inject.Inject

data class EditRecipeUiState(
    val isLoading: Boolean = true,
    val recipeId: Long = 0,
    val isNewRecipe: Boolean = true,
    val title: String = "",
    val description: String = "",
    val servingsText: String = "4",
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
    /** Quantity text as typed, one per ingredient, so partial input like "1." or "2-" survives. */
    val quantityTexts: List<String> = emptyList(),
    val steps: List<StepEntity> = emptyList(),
    val allTags: List<TagEntity> = emptyList(),
    val allDiet: List<TagEntity> = emptyList(),
    val allEquipment: List<TagEntity> = emptyList(),
    val allConsumables: List<TagEntity> = emptyList(),
    val selectedTagIds: Set<Long> = emptySet(),
    val isExtractingText: Boolean = false,
    val extractError: String? = null,
    val isSaved: Boolean = false,
    val isSearchingAi: Boolean = false,
    val aiCandidates: List<WebRecipeSuggestion> = emptyList(),
    val aiSearchError: String? = null,
    val isFetchingCandidate: Boolean = false
)

@HiltViewModel
class EditRecipeViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val tagRepository: TagRepository,
    private val recipeExtractor: RecipeExtractor,
    private val chatPlanner: ChatPlanner,
    private val webRecipeFetcher: WebRecipeFetcher,
    private val settingsStore: SettingsStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val recipeId: Long = savedStateHandle["recipeId"] ?: 0L

    /** The running Find with AI search or candidate fetch, cancelled when the dialog closes. */
    private var aiJob: Job? = null

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
                    allTags = allTags,
                    allDiet = allDiet,
                    allEquipment = allEquipment,
                    allConsumables = allConsumables,
                    ingredients = listOf(IngredientEntity(recipeId = 0, position = 0, name = "", baseName = "", category = "pantry", rawText = "")),
                    quantityTexts = listOf(""),
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
                val formIngredients = ingredients.ifEmpty {
                    listOf(IngredientEntity(recipeId = recipeId, position = 0, name = "", baseName = "", category = "pantry", rawText = ""))
                }

                _uiState.value = EditRecipeUiState(
                    isLoading = false,
                    recipeId = recipeId,
                    isNewRecipe = false,
                    title = recipe.title.orEmpty(),
                    description = recipe.description.orEmpty(),
                    servingsText = (recipe.servings ?: 4).toString(),
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
                    ingredients = formIngredients,
                    quantityTexts = formIngredients.map { formatQuantityText(it.quantity, it.quantityMax) },
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
    fun updateServings(text: String) {
        _uiState.value = _uiState.value.copy(servingsText = text.filter { it.isDigit() }.take(2))
    }
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
        _uiState.value = _uiState.value.copy(ingredients = current, quantityTexts = _uiState.value.quantityTexts + "")
    }

    fun updateIngredient(index: Int, name: String, quantityText: String, unit: String?, note: String?, groupName: String?) {
        val current = _uiState.value.ingredients.toMutableList()
        if (index in current.indices) {
            val (quantity, quantityMax) = parseQuantityText(quantityText)
            val trimmedUnit = unit?.trim()?.ifEmpty { null }
            current[index] = current[index].copy(
                name = name,
                baseName = name.trim().lowercase(),
                quantity = quantity,
                quantityMax = quantityMax,
                unit = trimmedUnit,
                note = note?.trim()?.ifEmpty { null },
                groupName = groupName?.trim()?.ifEmpty { null },
                rawText = listOf(formatQuantityText(quantity, quantityMax), trimmedUnit.orEmpty(), name.trim())
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
            )
            val texts = _uiState.value.quantityTexts.toMutableList()
            while (texts.size < current.size) texts.add("")
            texts[index] = quantityText
            _uiState.value = _uiState.value.copy(ingredients = current, quantityTexts = texts)
        }
    }

    fun removeIngredient(index: Int) {
        val current = _uiState.value.ingredients.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            val texts = _uiState.value.quantityTexts.toMutableList()
            if (index in texts.indices) texts.removeAt(index)
            _uiState.value = _uiState.value.copy(ingredients = current, quantityTexts = texts)
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

                val activeModel = settingsStore.modelIdFlow.first()
                result.fold(
                    onSuccess = { extracted ->
                        recipeRepository.logUsage(extracted.usage.toExtractionUsageEntity(activeModel))
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
                            servingsText = extracted.servings?.takeIf { it > 0 }?.toString() ?: _uiState.value.servingsText,
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
                            quantityTexts = if (ingredientEntities.isNotEmpty()) {
                                ingredientEntities.map { formatQuantityText(it.quantity, it.quantityMax) }
                            } else _uiState.value.quantityTexts,
                            steps = if (stepEntities.isNotEmpty()) stepEntities else _uiState.value.steps,
                            selectedTagIds = matchedTagIds
                        )
                        onFinished()
                    },
                    onFailure = { err ->
                        if (err is NoRecipeFoundException) {
                            err.usage?.let { recipeRepository.logUsage(it.toExtractionUsageEntity(activeModel)) }
                        }
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

    fun searchFindWithAi(dishName: String, hints: String = "") {
        val state = _uiState.value
        if (dishName.isBlank() || state.isSearchingAi || state.isFetchingCandidate) return
        aiJob?.cancel()
        _uiState.value = state.copy(isSearchingAi = true, aiSearchError = null, aiCandidates = emptyList())

        aiJob = viewModelScope.launch {
            val maxSearches = settingsStore.maxWebSearchesFlow.first()
            val result = try {
                chatPlanner.findWebCandidates(dishName.trim(), hints.trim(), maxSearches)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.getOrNull()?.let { logWebUsage(it.usage) }
            val candidates = result.getOrNull()?.picks.orEmpty()
            _uiState.value = _uiState.value.copy(
                isSearchingAi = false,
                aiCandidates = candidates,
                aiSearchError = when {
                    result.isFailure -> llmErrorMessage(result.exceptionOrNull())
                    candidates.isEmpty() -> "No matching recipes found online. Try different keywords or hints."
                    else -> null
                }
            )
        }
    }

    /**
     * Fetches [candidate] through the website importer and extractor, then fills the form without
     * saving. With [replaceExisting] off, only empty fields are filled and chosen tags are kept. A page
     * that can't be fetched or holds no recipe is dropped from the list so the next one can be tried.
     */
    fun applyAiCandidate(candidate: WebRecipeSuggestion, replaceExisting: Boolean, onFinished: () -> Unit) {
        if (_uiState.value.isFetchingCandidate) return
        _uiState.value = _uiState.value.copy(isFetchingCandidate = true, aiSearchError = null)

        aiJob = viewModelScope.launch {
            val siteName = candidate.site.ifBlank { "that site" }
            val webData = try {
                webRecipeFetcher.fetch(candidate.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (webData == null || webData.sourceText.isBlank()) {
                dropCandidate(candidate, "Couldn't open the page on $siteName.")
                return@launch
            }

            val activeModel = settingsStore.modelIdFlow.first()
            val result = try {
                recipeExtractor.extract(
                    ExtractionInput(
                        sourceType = SourceType.WEB,
                        sourceText = webData.sourceText,
                        author = webData.author,
                        allowedTags = tagRepository.getTagsByKind(TagEntity.KIND_TAG).map { it.name },
                        allowedDiet = tagRepository.getTagsByKind(TagEntity.KIND_DIET).map { it.name },
                        allowedEquipment = tagRepository.getTagsByKind(TagEntity.KIND_EQUIPMENT).map { it.name },
                        allowedConsumables = tagRepository.getTagsByKind(TagEntity.KIND_CONSUMABLE).map { it.name },
                        allowNewTags = settingsStore.allowNewAiTagsFlow.first()
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

            result.fold(
                onSuccess = { extracted ->
                    recipeRepository.logUsage(extracted.usage.toExtractionUsageEntity(activeModel))
                    val tagIdMap = tagRepository.getAllTags().associate { (it.name.lowercase() to it.kind) to it.id }
                    val matchedTagIds = buildSet {
                        extracted.tags.forEach { tagIdMap[it.lowercase() to TagEntity.KIND_TAG]?.let(::add) }
                        extracted.contains.forEach { tagIdMap[it.lowercase() to TagEntity.KIND_DIET]?.let(::add) }
                        extracted.equipment.forEach { tagIdMap[it.lowercase() to TagEntity.KIND_EQUIPMENT]?.let(::add) }
                        extracted.consumables.forEach { tagIdMap[it.lowercase() to TagEntity.KIND_CONSUMABLE]?.let(::add) }
                    }
                    _uiState.value = mergeExtracted(
                        state = _uiState.value,
                        extracted = extracted,
                        matchedTagIds = matchedTagIds,
                        sourceUrl = candidate.url,
                        fallbackTitle = candidate.title,
                        replaceExisting = replaceExisting
                    ).copy(isFetchingCandidate = false, aiCandidates = emptyList(), aiSearchError = null)
                    onFinished()
                },
                onFailure = { err ->
                    if (err is NoRecipeFoundException) {
                        err.usage?.let { recipeRepository.logUsage(it.toExtractionUsageEntity(activeModel)) }
                        dropCandidate(candidate, "No full recipe found on $siteName.")
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isFetchingCandidate = false,
                            aiSearchError = llmErrorMessage(err)
                        )
                    }
                }
            )
        }
    }

    private fun dropCandidate(candidate: WebRecipeSuggestion, reason: String) {
        val remaining = _uiState.value.aiCandidates.filterNot { it.url == candidate.url }
        _uiState.value = _uiState.value.copy(
            isFetchingCandidate = false,
            aiCandidates = remaining,
            aiSearchError = reason + if (remaining.isNotEmpty()) " Try the next candidate." else " Try another search."
        )
    }

    private suspend fun logWebUsage(usage: LlmUsage) {
        if (usage.inputTokens == 0 && usage.outputTokens == 0 && usage.webSearches == 0) return
        recipeRepository.logUsage(
            LlmUsageEntity(
                purpose = LlmUsageEntity.PURPOSE_WEB,
                model = usage.model ?: settingsStore.chatModelIdFlow.first(),
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                cacheReadTokens = usage.cacheReadTokens,
                webSearches = usage.webSearches
            )
        )
    }

    fun fillMissingMetadataWithAi(onFinished: () -> Unit) {
        val state = _uiState.value
        val titleText = state.title.trim()
        if (titleText.isEmpty()) {
            _uiState.value = _uiState.value.copy(extractError = "Enter a recipe title first")
            return
        }

        _uiState.value = _uiState.value.copy(isExtractingText = true, extractError = null)

        viewModelScope.launch {
            try {
                val ingredientsSummary = state.ingredients.filter { it.name.isNotBlank() }
                    .joinToString("\n") { "- ${it.name} ${it.rawText}".trim() }
                val stepsSummary = state.steps.filter { it.text.isNotBlank() }
                    .joinToString("\n") { it.text }

                val sourceText = buildString {
                    appendLine("Title: $titleText")
                    if (state.description.isNotBlank()) appendLine("Description: ${state.description}")
                    if (ingredientsSummary.isNotBlank()) {
                        appendLine("Ingredients:")
                        appendLine(ingredientsSummary)
                    }
                    if (stepsSummary.isNotBlank()) {
                        appendLine("Steps:")
                        appendLine(stepsSummary)
                    }
                }

                val allTags = tagRepository.getTagsByKind(TagEntity.KIND_TAG).map { it.name }
                val allDiet = tagRepository.getTagsByKind(TagEntity.KIND_DIET).map { it.name }
                val allEquipment = tagRepository.getTagsByKind(TagEntity.KIND_EQUIPMENT).map { it.name }
                val allConsumables = tagRepository.getTagsByKind(TagEntity.KIND_CONSUMABLE).map { it.name }

                val input = ExtractionInput(
                    sourceType = SourceType.MANUAL,
                    sourceText = sourceText,
                    allowedTags = allTags,
                    allowedDiet = allDiet,
                    allowedEquipment = allEquipment,
                    allowedConsumables = allConsumables,
                    allowNewTags = false
                )

                val result = recipeExtractor.extract(input)
                result.fold(
                    onSuccess = { extracted ->
                        val activeModel = settingsStore.modelIdFlow.first()
                        recipeRepository.logUsage(extracted.usage.toExtractionUsageEntity(activeModel))

                        val matchedTagIds = state.selectedTagIds.toMutableSet()
                        state.allTags.filter { extracted.tags.contains(it.name) }.forEach { matchedTagIds.add(it.id) }
                        state.allDiet.filter { extracted.contains.contains(it.name) }.forEach { matchedTagIds.add(it.id) }
                        state.allEquipment.filter { extracted.equipment.contains(it.name) }.forEach { matchedTagIds.add(it.id) }
                        state.allConsumables.filter { extracted.consumables.contains(it.name) }.forEach { matchedTagIds.add(it.id) }

                        _uiState.value = _uiState.value.copy(
                            isExtractingText = false,
                            cuisine = state.cuisine.ifBlank { extracted.cuisine ?: "" },
                            prepMin = if (state.prepMin > 0) state.prepMin else (extracted.prepMinutes ?: 0),
                            cookMin = if (state.cookMin > 0) state.cookMin else (extracted.cookMinutes ?: 0),
                            totalMin = if (state.totalMin > 0) state.totalMin else (extracted.totalMinutes ?: 0),
                            complexity = state.complexity.ifBlank { extracted.complexity ?: "" },
                            complexityReason = state.complexityReason.ifBlank { extracted.complexityReason ?: "" },
                            spiceLevel = state.spiceLevel.ifBlank { extracted.spiceLevel ?: "" },
                            dietType = state.dietType.ifBlank { extracted.dietType ?: "" },
                            kcalPerServing = if (state.kcalPerServing > 0) state.kcalPerServing else (extracted.kcalPerServing ?: 0),
                            makeAhead = state.makeAhead || extracted.makeAhead,
                            freezerFriendly = state.freezerFriendly || extracted.freezerFriendly,
                            selectedTagIds = matchedTagIds
                        )
                        onFinished()
                    },
                    onFailure = { err ->
                        _uiState.value = _uiState.value.copy(
                            isExtractingText = false,
                            extractError = err.message ?: "Failed to fill metadata with AI"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isExtractingText = false,
                    extractError = e.message ?: "Error processing metadata"
                )
            }
        }
    }

    fun clearAiCandidates() {
        aiJob?.cancel()
        aiJob = null
        _uiState.value = _uiState.value.copy(
            aiCandidates = emptyList(),
            aiSearchError = null,
            isSearchingAi = false,
            isFetchingCandidate = false
        )
    }

    fun saveRecipe(onSuccess: (Long) -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            val now = System.currentTimeMillis()
            val servings = (state.servingsText.toIntOrNull() ?: 4).coerceIn(1, 50)

            val recipeToSave = if (state.isNewRecipe) {
                RecipeEntity(
                    sourceType = "manual",
                    sourceKey = UUID.randomUUID().toString(),
                    sourceUrl = null,
                    authorHandle = null,
                    captionRaw = state.captionRaw.ifBlank { null },
                    title = state.title.trim().ifEmpty { "Untitled Recipe" },
                    description = state.description.trim().takeIf { it.isNotEmpty() },
                    servings = servings,
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
                    servings = servings,
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
                    captionRaw = state.captionRaw.ifBlank { null } ?: existing.captionRaw,
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
                tagIds = state.selectedTagIds.toList(),
                source = RecipeTagEntity.SOURCE_USER
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

    companion object {
        /** True when the form holds ingredients, steps or a description that Find with AI could overwrite. */
        fun formHasContent(state: EditRecipeUiState): Boolean =
            state.description.isNotBlank() ||
                state.ingredients.any { it.name.isNotBlank() } ||
                state.steps.any { it.text.isNotBlank() }

        /**
         * Applies an extracted recipe to the form. [replaceExisting] lets extracted values win wherever
         * present; otherwise only empty fields, an empty ingredient list (with servings) and an empty
         * step list are filled, and tags are added to the current selection.
         */
        fun mergeExtracted(
            state: EditRecipeUiState,
            extracted: ExtractedRecipe,
            matchedTagIds: Set<Long>,
            sourceUrl: String,
            fallbackTitle: String,
            replaceExisting: Boolean
        ): EditRecipeUiState {
            var position = 0
            val ingredients = extracted.ingredientGroups.flatMap { group ->
                group.items.map { item ->
                    IngredientEntity(
                        recipeId = state.recipeId,
                        position = position++,
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
            val steps = extracted.steps.mapIndexed { index, step ->
                StepEntity(
                    recipeId = state.recipeId,
                    position = index,
                    text = step.text,
                    timerSeconds = step.timerSeconds?.takeIf { it > 0 }
                )
            }

            fun text(current: String, new: String?): String {
                val value = new?.trim().orEmpty()
                return if (value.isNotEmpty() && (replaceExisting || current.isBlank())) value else current
            }
            fun number(current: Int, new: Int?): Int {
                val value = new?.takeIf { it > 0 } ?: return current
                return if (replaceExisting || current <= 0) value else current
            }

            val fillIngredients = ingredients.isNotEmpty() &&
                (replaceExisting || state.ingredients.none { it.name.isNotBlank() })
            val fillSteps = steps.isNotEmpty() && (replaceExisting || state.steps.none { it.text.isNotBlank() })
            val servings = extracted.servings?.takeIf { it > 0 }

            return state.copy(
                title = text(state.title, extracted.title?.ifBlank { null } ?: fallbackTitle),
                description = text(state.description, extracted.description),
                // Servings travel with the ingredient quantities they describe.
                servingsText = if (fillIngredients && servings != null) servings.toString() else state.servingsText,
                prepMin = number(state.prepMin, extracted.prepMinutes),
                cookMin = number(state.cookMin, extracted.cookMinutes),
                totalMin = number(state.totalMin, extracted.totalMinutes),
                cuisine = text(state.cuisine, extracted.cuisine),
                complexity = text(state.complexity, extracted.complexity),
                complexityReason = text(state.complexityReason, extracted.complexityReason),
                spiceLevel = text(state.spiceLevel, extracted.spiceLevel),
                dietType = text(state.dietType, extracted.dietType),
                kcalPerServing = number(state.kcalPerServing, extracted.kcalPerServing),
                makeAhead = state.makeAhead || extracted.makeAhead,
                freezerFriendly = state.freezerFriendly || extracted.freezerFriendly,
                captionRaw = if (replaceExisting || state.captionRaw.isBlank()) sourceUrl else state.captionRaw,
                ingredients = if (fillIngredients) ingredients else state.ingredients,
                quantityTexts = if (fillIngredients) {
                    ingredients.map { formatQuantityText(it.quantity, it.quantityMax) }
                } else state.quantityTexts,
                steps = if (fillSteps) steps else state.steps,
                selectedTagIds = if (replaceExisting) matchedTagIds else state.selectedTagIds + matchedTagIds
            )
        }

        /** Parses "2", "1.5", "1,5" or a range "2-3" / "2–3" into (quantity, quantityMax). */
        fun parseQuantityText(text: String): Pair<Double?, Double?> {
            val parts = text.trim().split('-', '–').map { it.trim().replace(',', '.') }
            val quantity = parts.firstOrNull()?.toDoubleOrNull()?.takeIf { it > 0 }
            val quantityMax = if (quantity != null && parts.size == 2) {
                parts[1].toDoubleOrNull()?.takeIf { it > quantity }
            } else null
            return quantity to quantityMax
        }

        fun formatQuantityText(quantity: Double?, quantityMax: Double?): String {
            if (quantity == null) return ""
            return listOfNotNull(quantity, quantityMax).joinToString("–") { formatNumber(it) }
        }

        private fun formatNumber(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }
}
