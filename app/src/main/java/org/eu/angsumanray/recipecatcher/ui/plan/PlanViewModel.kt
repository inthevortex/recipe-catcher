package org.eu.angsumanray.recipecatcher.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.core.model.PlanEntry
import org.eu.angsumanray.recipecatcher.core.model.PlanSlot
import org.eu.angsumanray.recipecatcher.core.planner.AvailableLeftover
import org.eu.angsumanray.recipecatcher.core.planner.PortionLedger
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanEntryEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanSlotEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.repo.PlannerRepository
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import org.eu.angsumanray.recipecatcher.reminders.ReminderScheduler
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
    val snackbarMessage: String? = null
)

@HiltViewModel
class PlanViewModel @Inject constructor(
    private val plannerRepository: PlannerRepository,
    private val settingsStore: SettingsStore,
    private val reminderScheduler: ReminderScheduler
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

            // Calculate leftovers for selected date
            val allCoreSlots = slots.map {
                PlanSlot(id = it.id, date = it.date.toString(), name = it.name, time = it.time?.toString())
            }
            val allCoreEntries = entries.map {
                PlanEntry(
                    id = it.id,
                    slotId = it.slotId,
                    kind = it.kind,
                    recipeId = it.recipeId,
                    portions = it.portions,
                    portionsEatenNow = it.portionsEatenNow,
                    fromEntryId = it.fromEntryId
                )
            }
            val leftovers = PortionLedger.availableLeftovers(
                _uiState.value.selectedDate.toString(),
                allCoreSlots,
                allCoreEntries
            )

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
            plannerRepository.deleteSlot(slot)
            loadData()
        }
    }

    fun addCookEntry(slotId: Long, recipeId: Long, portions: Int, portionsEatenNow: Int) {
        viewModelScope.launch {
            plannerRepository.addCookEntry(slotId, recipeId, portions, portionsEatenNow)
            reminderScheduler.rescheduleAll()
            loadData()
        }
    }

    fun addPrepEntry(slotId: Long, recipeId: Long?, text: String?, portions: Int, prepNightBefore: Boolean) {
        viewModelScope.launch {
            plannerRepository.addPrepEntry(slotId, recipeId, text, portions, prepNightBefore)
            reminderScheduler.rescheduleAll()
            loadData()
        }
    }

    fun addLeftoverEntry(slotId: Long, fromEntryId: Long, portions: Int) {
        viewModelScope.launch {
            plannerRepository.addLeftoverEntry(slotId, fromEntryId, portions)
            reminderScheduler.rescheduleAll()
            loadData()
        }
    }

    fun moveEntry(entryId: Long, newSlotId: Long) {
        viewModelScope.launch {
            val targetEntries = plannerRepository.getEntriesForSlot(newSlotId)
            plannerRepository.moveEntry(entryId, newSlotId, targetEntries.size)
            reminderScheduler.rescheduleAll()
            loadData()
        }
    }

    fun deleteEntry(entry: PlanEntryEntity) {
        viewModelScope.launch {
            plannerRepository.deleteEntry(entry)
            reminderScheduler.rescheduleAll()
            loadData()
        }
    }

    fun toggleEntryDone(entryId: Long) {
        viewModelScope.launch {
            plannerRepository.toggleEntryDone(entryId)
            reminderScheduler.rescheduleAll()
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
}
