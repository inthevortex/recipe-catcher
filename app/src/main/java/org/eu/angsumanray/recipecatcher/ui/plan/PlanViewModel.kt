package org.eu.angsumanray.recipecatcher.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.core.planner.AvailableLeftover
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanEntryEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanSlotEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.repo.PlannerRepository
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import org.eu.angsumanray.recipecatcher.core.llm.EmptyPlanSlot
import org.eu.angsumanray.recipecatcher.core.llm.ExistingPlanAssignment
import org.eu.angsumanray.recipecatcher.core.llm.PlanSuggester
import org.eu.angsumanray.recipecatcher.core.llm.PlanSuggesterInput
import org.eu.angsumanray.recipecatcher.core.llm.ProposedSlotAssignment
import org.eu.angsumanray.recipecatcher.core.llm.RecipeIndexEntry
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.LlmUsageEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

data class PlanUiState(
    val isLoading: Boolean = true,
    val selectedDate: LocalDate = LocalDate.now(),
    val weekStartDate: LocalDate = LocalDate.now(),
    val weekSlots: List<PlanSlotEntity> = emptyList(),
    val weekEntries: List<PlanEntryEntity> = emptyList(),
    val allRecipes: List<RecipeEntity> = emptyList(),
    val availableLeftovers: List<AvailableLeftover> = emptyList(),
    val pendingDelete: PendingDelete? = null,
    val snackbarMessage: String? = null,
    val isSuggesting: Boolean = false,
    val planSuggestions: List<ProposedSlotAssignment> = emptyList(),
    val showSuggestionsDialog: Boolean = false
)

/** A delete waiting on the user's answer about the leftover entries that draw on it. */
sealed class PendingDelete {
    abstract val leftoverCount: Int

    data class Entry(val entry: PlanEntryEntity, override val leftoverCount: Int) : PendingDelete()
    data class Slot(val slot: PlanSlotEntity, override val leftoverCount: Int) : PendingDelete()
}

@HiltViewModel
class PlanViewModel @Inject constructor(
    private val plannerRepository: PlannerRepository,
    private val settingsStore: SettingsStore,
    private val planSuggester: PlanSuggester,
    private val database: RecipeDatabase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlanUiState())
    val uiState: StateFlow<PlanUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val weekStartsOn = settingsStore.weekStartsOnFlow.first()
            val startDay = if (weekStartsOn.equals("Sunday", ignoreCase = true)) {
                DayOfWeek.SUNDAY
            } else {
                DayOfWeek.MONDAY
            }
            val today = LocalDate.now()
            val weekStart = today.with(TemporalAdjusters.previousOrSame(startDay))

            _uiState.value = _uiState.value.copy(
                selectedDate = today,
                weekStartDate = weekStart
            )

            plannerRepository.ensureDefaultSlotsForWeek(weekStart, 7)
            loadData()
        }
    }

    private fun loadData() {
        val weekStart = _uiState.value.weekStartDate
        val weekEnd = weekStart.plusDays(6)

        viewModelScope.launch {
            val slots = plannerRepository.getSlotsBetween(weekStart, weekEnd)
            val entries = plannerRepository.getEntriesBetween(weekStart, weekEnd)
            val recipes = plannerRepository.getAllRecipes()
            // Looks past the week boundary, so Sunday's cook can feed Monday's lunch.
            val leftovers = plannerRepository.availableLeftovers(_uiState.value.selectedDate)

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                weekSlots = slots,
                weekEntries = entries,
                allRecipes = recipes,
                availableLeftovers = leftovers
            )
        }
    }

    fun selectDate(date: LocalDate) {
        _uiState.value = _uiState.value.copy(selectedDate = date)
        loadData()
    }

    fun nextWeek() {
        val newWeekStart = _uiState.value.weekStartDate.plusWeeks(1)
        _uiState.value = _uiState.value.copy(
            weekStartDate = newWeekStart,
            selectedDate = newWeekStart
        )
        viewModelScope.launch {
            plannerRepository.ensureDefaultSlotsForWeek(newWeekStart, 7)
            loadData()
        }
    }

    fun prevWeek() {
        val newWeekStart = _uiState.value.weekStartDate.minusWeeks(1)
        _uiState.value = _uiState.value.copy(
            weekStartDate = newWeekStart,
            selectedDate = newWeekStart
        )
        viewModelScope.launch {
            plannerRepository.ensureDefaultSlotsForWeek(newWeekStart, 7)
            loadData()
        }
    }

    fun jumpToToday() {
        val today = LocalDate.now()
        viewModelScope.launch {
            val weekStartsOn = settingsStore.weekStartsOnFlow.first()
            val startDay = if (weekStartsOn.equals("Sunday", ignoreCase = true)) {
                DayOfWeek.SUNDAY
            } else {
                DayOfWeek.MONDAY
            }
            val weekStart = today.with(TemporalAdjusters.previousOrSame(startDay))
            _uiState.value = _uiState.value.copy(
                selectedDate = today,
                weekStartDate = weekStart
            )
            plannerRepository.ensureDefaultSlotsForWeek(weekStart, 7)
            loadData()
        }
    }

    fun addSlot(date: LocalDate, name: String, time: LocalTime?) {
        viewModelScope.launch {
            val currentSlots = plannerRepository.getSlotsForDate(date)
            val slot = PlanSlotEntity(
                date = date,
                name = name.trim(),
                time = time,
                position = currentSlots.size
            )
            plannerRepository.insertSlot(slot)
            loadData()
        }
    }

    fun deleteSlot(slot: PlanSlotEntity) {
        viewModelScope.launch {
            val leftovers = plannerRepository.countLeftoversDependingOnSlot(slot)
            if (leftovers > 0) {
                _uiState.value = _uiState.value.copy(pendingDelete = PendingDelete.Slot(slot, leftovers))
            } else {
                plannerRepository.deleteSlot(slot)
                loadData()
            }
        }
    }

    fun addCookEntry(slotId: Long, recipeId: Long, portions: Int, portionsEatenNow: Int) {
        viewModelScope.launch {
            plannerRepository.addCookEntry(slotId, recipeId, portions, portionsEatenNow)
            loadData()
        }
    }

    fun addPrepEntry(slotId: Long, recipeId: Long?, text: String?, portions: Int, prepNightBefore: Boolean) {
        viewModelScope.launch {
            plannerRepository.addPrepEntry(slotId, recipeId, text, portions, prepNightBefore)
            loadData()
        }
    }

    fun addLeftoverEntry(slotId: Long, fromEntryId: Long, portions: Int) {
        viewModelScope.launch {
            plannerRepository.addLeftoverEntry(slotId, fromEntryId, portions)
            loadData()
        }
    }

    fun moveEntry(entryId: Long, newSlotId: Long) {
        viewModelScope.launch {
            val targetEntries = plannerRepository.getEntriesForSlot(newSlotId)
            plannerRepository.moveEntry(entryId, newSlotId, targetEntries.size)
            loadData()
        }
    }

    fun deleteEntry(entry: PlanEntryEntity) {
        viewModelScope.launch {
            val leftovers = plannerRepository.countLeftoversDependingOn(entry)
            if (leftovers > 0) {
                _uiState.value = _uiState.value.copy(pendingDelete = PendingDelete.Entry(entry, leftovers))
            } else {
                plannerRepository.deleteEntry(entry)
                loadData()
            }
        }
    }

    /** Finishes a delete that has leftovers depending on it, removing them too or keeping them. */
    fun confirmPendingDelete(removeLeftovers: Boolean) {
        val pending = _uiState.value.pendingDelete ?: return
        _uiState.value = _uiState.value.copy(pendingDelete = null)
        viewModelScope.launch {
            when (pending) {
                is PendingDelete.Entry -> plannerRepository.deleteEntry(pending.entry, removeLeftovers)
                is PendingDelete.Slot -> plannerRepository.deleteSlot(pending.slot, removeLeftovers)
            }
            loadData()
        }
    }

    fun dismissPendingDelete() {
        _uiState.value = _uiState.value.copy(pendingDelete = null)
    }

    fun toggleEntryDone(entryId: Long) {
        viewModelScope.launch {
            plannerRepository.toggleEntryDone(entryId)
            loadData()
        }
    }

    fun copyLastWeekLayout() {
        viewModelScope.launch {
            val currentWeek = _uiState.value.weekStartDate
            val success = plannerRepository.copyPreviousWeekLayout(currentWeek)
            _uiState.value = _uiState.value.copy(
                snackbarMessage = if (success) "Copied previous week's slots!" else "No slots found in previous week."
            )
            loadData()
        }
    }

    fun clearSnackbar() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }

    fun requestPlanSuggestions() {
        val state = _uiState.value
        val slots = state.weekSlots
        val entries = state.weekEntries

        val occupiedSlotIds = entries.map { it.slotId }.toSet()
        val emptySlotEntities = slots.filter { it.id !in occupiedSlotIds }
        if (emptySlotEntities.isEmpty()) {
            _uiState.value = state.copy(snackbarMessage = "No empty slots to fill this week!")
            return
        }

        if (state.allRecipes.isEmpty()) {
            _uiState.value = state.copy(snackbarMessage = "No saved recipes in library to suggest from.")
            return
        }

        _uiState.value = state.copy(isSuggesting = true)

        viewModelScope.launch {
            try {
                val emptySlots = emptySlotEntities.map { slot ->
                    EmptyPlanSlot(
                        slotId = slot.id,
                        day = slot.date.toString(),
                        slotName = slot.name
                    )
                }

                val recipesMap = state.allRecipes.associateBy { it.id }
                val slotsMap = slots.associateBy { it.id }
                val existingAssignments = entries.mapNotNull { entry ->
                    val slot = slotsMap[entry.slotId] ?: return@mapNotNull null
                    ExistingPlanAssignment(
                        slotId = entry.slotId,
                        recipeId = entry.recipeId,
                        recipeTitle = entry.text ?: entry.recipeId?.let { recipesMap[it]?.title } ?: "Planned Meal",
                        day = slot.date.toString(),
                        slotName = slot.name
                    )
                }

                val inventory = database.listsDao().getAllInventoryItems()
                val inStock = inventory.filter { it.status == "in_stock" }.map { it.name }
                val lowStock = inventory.filter { it.status == "low" }.map { it.name }
                val leftovers = state.availableLeftovers.map {
                    val title = it.recipeId?.let { rId -> recipesMap[rId]?.title } ?: "Leftover"
                    "$title (${it.remainingPortions} portions left)"
                }

                val recipeIndexEntries = state.allRecipes.map { recipe ->
                    val ingredients = database.ingredientDao().getByRecipeId(recipe.id)
                    val tags = database.recipeTagDao().getTagsForRecipe(recipe.id)
                    val lastCookedDate = database.cookLogDao().getLastCookedDate(recipe.id)
                    val mainIngredients = ingredients.map { it.name }.filter { it.isNotBlank() }

                    RecipeIndexEntry(
                        id = recipe.id,
                        title = recipe.title.orEmpty(),
                        tags = tags.map { it.name },
                        mainIngredients = mainIngredients,
                        totalMinutes = recipe.totalMin,
                        complexity = recipe.complexity,
                        spiceLevel = recipe.spiceLevel,
                        dietType = recipe.dietType,
                        contains = emptyList(),
                        kcalPerServing = recipe.kcalPerServing,
                        makeAhead = recipe.makeAhead,
                        freezerFriendly = recipe.freezerFriendly,
                        equipment = emptyList(),
                        favourite = recipe.isFavourite,
                        lastCookedDate = lastCookedDate
                    )
                }

                val input = PlanSuggesterInput(
                    emptySlots = emptySlots,
                    existingAssignments = existingAssignments,
                    libraryRecipes = recipeIndexEntries,
                    inStockIngredients = inStock,
                    lowStockIngredients = lowStock,
                    availableLeftovers = leftovers
                )

                val result = planSuggester.suggestMeals(input)
                result.fold(
                    onSuccess = { planResult ->
                        if (planResult.usage.inputTokens > 0) {
                            val activeModel = settingsStore.modelIdFlow.first()
                            database.llmUsageDao().insert(
                                LlmUsageEntity(
                                    purpose = LlmUsageEntity.PURPOSE_PLANNING,
                                    model = planResult.usage.model ?: activeModel,
                                    inputTokens = planResult.usage.inputTokens,
                                    outputTokens = planResult.usage.outputTokens,
                                    cacheReadTokens = planResult.usage.cacheReadTokens,
                                    webSearches = 0
                                )
                            )
                        }

                        if (planResult.suggestions.isEmpty()) {
                            _uiState.value = _uiState.value.copy(
                                isSuggesting = false,
                                snackbarMessage = "No suggestions could be generated."
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(
                                isSuggesting = false,
                                planSuggestions = planResult.suggestions,
                                showSuggestionsDialog = true
                            )
                        }
                    },
                    onFailure = { err ->
                        _uiState.value = _uiState.value.copy(
                            isSuggesting = false,
                            snackbarMessage = "Plan suggestion error: ${err.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSuggesting = false,
                    snackbarMessage = e.message ?: "Failed to generate suggestions"
                )
            }
        }
    }

    fun applyPlanSuggestions(selected: List<ProposedSlotAssignment>) {
        if (selected.isEmpty()) {
            dismissSuggestionsDialog()
            return
        }

        viewModelScope.launch {
            for (item in selected) {
                plannerRepository.addCookEntry(
                    slotId = item.slotId,
                    recipeId = item.recipeId,
                    portions = item.portions
                )
            }
            _uiState.value = _uiState.value.copy(
                showSuggestionsDialog = false,
                planSuggestions = emptyList(),
                snackbarMessage = "Added ${selected.size} suggested meals to plan!"
            )
            loadData()
        }
    }

    fun dismissSuggestionsDialog() {
        _uiState.value = _uiState.value.copy(
            showSuggestionsDialog = false,
            planSuggestions = emptyList()
        )
    }
}
