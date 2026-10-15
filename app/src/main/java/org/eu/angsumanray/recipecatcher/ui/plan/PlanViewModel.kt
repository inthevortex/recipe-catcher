package org.eu.angsumanray.recipecatcher.ui.plan

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
import org.eu.angsumanray.recipecatcher.core.llm.llmErrorMessage
import org.eu.angsumanray.recipecatcher.data.db.entity.InventoryItemEntity
import org.eu.angsumanray.recipecatcher.data.repo.RecipeIndexBuilder
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
    val recipeTags: Map<Long, Set<String>> = emptyMap(),
    val availableLeftovers: List<AvailableLeftover> = emptyList(),
    val pendingDelete: PendingDelete? = null,
    val snackbarMessage: String? = null,
    val isSuggesting: Boolean = false,
    val planSuggestions: List<ProposedSlotAssignment> = emptyList(),
    /** The chosen suggestion per slot ID. */
    val suggestionSelection: Map<Long, ProposedSlotAssignment> = emptyMap(),
    val suggestionsForEmptySlotsOnly: Boolean = true,
    val isApplyingSuggestions: Boolean = false,
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
    private val recipeIndexBuilder: RecipeIndexBuilder,
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

            val allTags = database.tagDao().getAll().associateBy { it.id }
            val allRecipeTags = database.recipeTagDao().getAll()
            val recipeTagsMap = mutableMapOf<Long, MutableSet<String>>()
            for (rt in allRecipeTags) {
                val tagName = allTags[rt.tagId]?.name?.lowercase() ?: continue
                recipeTagsMap.getOrPut(rt.recipeId) { mutableSetOf() }.add(tagName)
            }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                weekSlots = slots,
                weekEntries = entries,
                allRecipes = recipes,
                recipeTags = recipeTagsMap,
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
            try {
                plannerRepository.addLeftoverEntry(slotId, fromEntryId, portions)
            } catch (e: IllegalArgumentException) {
                _uiState.value = _uiState.value.copy(snackbarMessage = e.message ?: "Those leftovers are no longer available")
            }
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

    /**
     * Asks Claude for library recipes: for one slot when [slotId] is given, otherwise for every empty
     * slot from today on ("Fill empty slots"). The dialog opens with one pick per slot preselected.
     */
    fun requestPlanSuggestions(slotId: Long? = null) {
        val state = _uiState.value
        if (state.isSuggesting || state.isApplyingSuggestions) return

        val today = LocalDate.now()
        val occupiedSlotIds = state.weekEntries.map { it.slotId }.toSet()
        val targetSlots = if (slotId != null) {
            state.weekSlots.filter { it.id == slotId && !it.date.isBefore(today) }
        } else {
            state.weekSlots.filter { it.id !in occupiedSlotIds && !it.date.isBefore(today) }
        }.sortedWith(compareBy({ it.date }, { it.position }))

        if (targetSlots.isEmpty()) {
            _uiState.value = state.copy(
                snackbarMessage = if (slotId != null) "Suggest works for today and later." else "No empty slots left this week."
            )
            return
        }
        if (state.allRecipes.isEmpty()) {
            _uiState.value = state.copy(snackbarMessage = "No saved recipes in library to suggest from.")
            return
        }

        Log.d(TAG, "requestPlanSuggestions slotId=$slotId, targetSlots=${targetSlots.size}")
        _uiState.value = state.copy(isSuggesting = true)

        viewModelScope.launch {
            try {
                val recipesMap = state.allRecipes.associateBy { it.id }
                val slotsMap = state.weekSlots.associateBy { it.id }
                val existingAssignments = state.weekEntries.mapNotNull { entry ->
                    val slot = slotsMap[entry.slotId] ?: return@mapNotNull null
                    ExistingPlanAssignment(
                        slotId = entry.slotId,
                        recipeId = entry.recipeId,
                        recipeTitle = entry.recipeId?.let { recipesMap[it]?.title } ?: entry.text ?: "Planned meal",
                        day = slot.date.toString(),
                        slotName = slot.name
                    )
                }

                val inventory = database.listsDao().getAllInventoryItems()
                val leftovers = state.availableLeftovers.filter { it.remainingPortions > 0 }
                val leftoverRecipeIds = leftovers.mapNotNull { it.recipeId }.toSet()

                val input = PlanSuggesterInput(
                    emptySlots = targetSlots.map { EmptyPlanSlot(slotId = it.id, day = "${it.date} (${it.date.dayOfWeek})", slotName = it.name) },
                    existingAssignments = existingAssignments,
                    libraryRecipes = recipeIndexBuilder.build(state.allRecipes),
                    inStockIngredients = inventory.filter { it.status == InventoryItemEntity.STATUS_IN_STOCK }.map { it.name },
                    lowStockIngredients = inventory.filter { it.status == InventoryItemEntity.STATUS_LOW }.map { it.name },
                    availableLeftovers = leftovers.map {
                        val title = it.recipeId?.let { rId -> recipesMap[rId]?.title }
                            ?: if (it.cookEntryId < 0) it.slotName else "Leftover"
                        "$title (${it.remainingPortions} portions left, recipe_id ${it.recipeId ?: "none"})"
                    },
                    leftoverRecipeIds = leftoverRecipeIds,
                    today = today.toString()
                )

                val result = planSuggester.suggestMeals(input)
                result.getOrNull()?.usage?.takeIf { it.inputTokens > 0 || it.outputTokens > 0 }?.let { usage ->
                    database.llmUsageDao().insert(
                        LlmUsageEntity(
                            purpose = LlmUsageEntity.PURPOSE_PLANNING,
                            model = usage.model ?: settingsStore.modelIdFlow.first(),
                            inputTokens = usage.inputTokens,
                            outputTokens = usage.outputTokens,
                            cacheReadTokens = usage.cacheReadTokens,
                            webSearches = 0
                        )
                    )
                }

                val suggestions = result.getOrNull()?.suggestions.orEmpty()
                if (result.isFailure) {
                    Log.e(TAG, "Meal suggestion failed", result.exceptionOrNull())
                } else {
                    Log.d(TAG, "Meal suggestion succeeded with ${suggestions.size} suggestions")
                }

                _uiState.value = when {
                    result.isFailure -> _uiState.value.copy(
                        isSuggesting = false,
                        snackbarMessage = "Suggest failed: ${llmErrorMessage(result.exceptionOrNull())}"
                    )
                    suggestions.isEmpty() -> _uiState.value.copy(
                        isSuggesting = false,
                        snackbarMessage = "No suggestions this time. Try again or add a meal yourself."
                    )
                    else -> {
                        val alreadyPlanned = state.weekEntries.mapNotNull { it.recipeId }.toSet()
                        val defaults = PlanSuggester.defaultSelection(
                            suggestions = suggestions,
                            slotOrder = targetSlots.map { it.id },
                            alreadyPlannedRecipeIds = alreadyPlanned,
                            leftoverRecipeIds = leftoverRecipeIds
                        )
                        _uiState.value.copy(
                            isSuggesting = false,
                            planSuggestions = suggestions,
                            suggestionSelection = defaults.associateBy { it.slotId },
                            suggestionsForEmptySlotsOnly = slotId == null,
                            showSuggestionsDialog = true
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exception requesting plan suggestions", e)
                _uiState.value = _uiState.value.copy(
                    isSuggesting = false,
                    snackbarMessage = "Suggest failed: ${llmErrorMessage(e)}"
                )
            }
        }
    }

    /** One pick per slot: choosing a suggestion replaces that slot's pick; choosing it again clears it. */
    fun toggleSuggestion(suggestion: ProposedSlotAssignment) {
        val selection = _uiState.value.suggestionSelection
        val updated = if (selection[suggestion.slotId] == suggestion) {
            selection - suggestion.slotId
        } else {
            selection + (suggestion.slotId to suggestion)
        }
        _uiState.value = _uiState.value.copy(suggestionSelection = updated)
    }

    fun applyPlanSuggestions() {
        val state = _uiState.value
        val selected = state.suggestionSelection.values.toList()
        if (state.isApplyingSuggestions) return
        if (selected.isEmpty()) {
            dismissSuggestionsDialog()
            return
        }

        _uiState.value = state.copy(isApplyingSuggestions = true)
        viewModelScope.launch {
            val message = try {
                val added = plannerRepository.addSuggestedCookEntries(
                    picks = selected.map { Triple(it.slotId, it.recipeId, it.portions) },
                    skipOccupiedSlots = state.suggestionsForEmptySlotsOnly
                )
                when {
                    added == selected.size -> "Added $added suggested ${if (added == 1) "meal" else "meals"} to the plan."
                    added == 0 -> "Those slots were filled in the meantime; nothing added."
                    else -> "Added $added of ${selected.size}; the rest were filled in the meantime."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't add the suggestions: ${e.message ?: "database error"}"
            }
            _uiState.value = _uiState.value.copy(
                isApplyingSuggestions = false,
                showSuggestionsDialog = false,
                planSuggestions = emptyList(),
                suggestionSelection = emptyMap(),
                snackbarMessage = message
            )
            loadData()
        }
    }

    fun dismissSuggestionsDialog() {
        if (_uiState.value.isApplyingSuggestions) return
        _uiState.value = _uiState.value.copy(
            showSuggestionsDialog = false,
            planSuggestions = emptyList(),
            suggestionSelection = emptyMap()
        )
    }

    private companion object {
        const val TAG = "PlanViewModel"
    }
}
