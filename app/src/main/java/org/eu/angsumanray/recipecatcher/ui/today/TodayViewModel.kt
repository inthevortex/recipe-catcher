package org.eu.angsumanray.recipecatcher.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanEntryEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanSlotEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.repo.PlannerRepository
import org.eu.angsumanray.recipecatcher.reminders.ReminderScheduler
import java.time.LocalDate
import javax.inject.Inject

data class TodayEntryItem(
    val entry: PlanEntryEntity,
    val slot: PlanSlotEntity,
    val recipe: RecipeEntity? = null,
    val isDone: Boolean = entry.doneAt != null
)

data class TodayUiState(
    val isLoading: Boolean = true,
    val date: LocalDate = LocalDate.now(),
    val slots: List<PlanSlotEntity> = emptyList(),
    val todayEntries: List<TodayEntryItem> = emptyList(),
    val tonightPrepEntries: List<TodayEntryItem> = emptyList()
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val plannerRepository: PlannerRepository,
    private val reminderScheduler: ReminderScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodayUiState())
    val uiState: StateFlow<TodayUiState> = _uiState.asStateFlow()

    init {
        loadToday()
    }

    fun loadToday() {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)

        viewModelScope.launch {
            // Ensure slots exist
            plannerRepository.ensureDefaultSlotsForDate(today)

            combine(
                plannerRepository.observeSlotsForDate(today),
                plannerRepository.observeEntriesForDate(today),
                plannerRepository.observeSlotsForDate(tomorrow),
                plannerRepository.observeEntriesForDate(tomorrow)
            ) { todaySlots, todayEntries, tomorrowSlots, tomorrowEntries ->
                val slotMap = (todaySlots + tomorrowSlots).associateBy { it.id }

                val todayItems = todayEntries.map { entry ->
                    val slot = slotMap[entry.slotId] ?: PlanSlotEntity(date = today, name = "Meal")
                    val recipe = entry.recipeId?.let { plannerRepository.getRecipe(it) }
                    TodayEntryItem(
                        entry = entry,
                        slot = slot,
                        recipe = recipe,
                        isDone = entry.doneAt != null
                    )
                }

                val tonightPrepItems = tomorrowEntries
                    .filter { it.prepNightBefore }
                    .map { entry ->
                        val slot = slotMap[entry.slotId] ?: PlanSlotEntity(date = tomorrow, name = "Tomorrow")
                        val recipe = entry.recipeId?.let { plannerRepository.getRecipe(it) }
                        TodayEntryItem(
                            entry = entry,
                            slot = slot,
                            recipe = recipe,
                            isDone = entry.doneAt != null
                        )
                    }

                TodayUiState(
                    isLoading = false,
                    date = today,
                    slots = todaySlots,
                    todayEntries = todayItems,
                    tonightPrepEntries = tonightPrepItems
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun toggleEntryDone(entryId: Long, photoPath: String? = null, note: String? = null) {
        viewModelScope.launch {
            plannerRepository.toggleEntryDone(entryId, photoPath, note)
            reminderScheduler.rescheduleAll()
        }
    }
}
