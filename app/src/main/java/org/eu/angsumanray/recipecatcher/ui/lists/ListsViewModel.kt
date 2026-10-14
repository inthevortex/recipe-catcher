package org.eu.angsumanray.recipecatcher.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.data.db.entity.GroceryItemEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.InventoryItemEntity
import org.eu.angsumanray.recipecatcher.data.repo.ListsRepository
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

data class ListsUiState(
    val isLoading: Boolean = true,
    val selectedTab: Int = 0, // 0: Grocery, 1: Inventory
    val currentWeekStart: LocalDate = LocalDate.now(),
    val groceryItems: List<GroceryItemEntity> = emptyList(),
    val inventoryItems: List<InventoryItemEntity> = emptyList(),
    val snackbarMessage: String? = null
)

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val listsRepository: ListsRepository,
    private val settingsStore: SettingsStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListsUiState())
    val uiState: StateFlow<ListsUiState> = _uiState.asStateFlow()

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

            _uiState.value = _uiState.value.copy(currentWeekStart = weekStart)

            combine(
                listsRepository.observeGroceryItems(weekStart),
                listsRepository.observeInventoryItems()
            ) { grocery, inventory ->
                _uiState.value.copy(
                    isLoading = false,
                    groceryItems = grocery,
                    inventoryItems = inventory
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun selectTab(tab: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun buildFromPlan() {
        viewModelScope.launch {
            val weekStart = _uiState.value.currentWeekStart
            listsRepository.buildFromPlan(weekStart)
            _uiState.value = _uiState.value.copy(
                snackbarMessage = "Grocery list generated from meal plan!"
            )
        }
    }

    fun toggleGroceryItem(id: Long, checked: Boolean) {
        viewModelScope.launch {
            listsRepository.toggleGroceryItemChecked(id, checked)
        }
    }

    fun clearCheckedGrocery() {
        viewModelScope.launch {
            listsRepository.clearCheckedGroceryItems(_uiState.value.currentWeekStart)
        }
    }

    fun addManualGroceryItem(name: String, category: String, quantityText: String?) {
        viewModelScope.launch {
            listsRepository.addManualGroceryItem(
                name = name,
                category = category,
                quantityText = quantityText,
                weekStart = _uiState.value.currentWeekStart
            )
        }
    }

    fun deleteGroceryItem(id: Long) {
        viewModelScope.launch {
            listsRepository.deleteGroceryItem(id)
        }
    }

    fun updateInventoryStatus(id: Long, status: String) {
        viewModelScope.launch {
            listsRepository.setInventoryStatus(id, status)
        }
    }

    fun addInventoryItem(name: String, category: String, status: String) {
        viewModelScope.launch {
            listsRepository.addInventoryItem(name = name, category = category, status = status)
        }
    }

    fun deleteInventoryItem(id: Long) {
        viewModelScope.launch {
            listsRepository.deleteInventoryItem(id)
        }
    }

    fun clearSnackbar() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }
}
