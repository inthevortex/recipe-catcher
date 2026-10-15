package org.eu.angsumanray.recipecatcher.ui.lists

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.capture.ImagePreprocessor
import org.eu.angsumanray.recipecatcher.core.llm.GroceryImageScanner
import org.eu.angsumanray.recipecatcher.core.llm.ScannedGroceryItem
import org.eu.angsumanray.recipecatcher.data.db.entity.CookedFoodItemEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.GroceryItemEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.InventoryItemEntity
import org.eu.angsumanray.recipecatcher.data.repo.ListsRepository
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager

data class ListsUiState(
    val isLoading: Boolean = true,
    val selectedTab: Int = 0, // 0: Grocery, 1: Pantry Staples, 2: Cooked Food
    val currentWeekStart: LocalDate = LocalDate.now(),
    val groceryItems: List<GroceryItemEntity> = emptyList(),
    val inventoryItems: List<InventoryItemEntity> = emptyList(),
    val cookedFoodItems: List<CookedFoodItemEntity> = emptyList(),
    val isScanningGrocery: Boolean = false,
    val scannedGroceryCandidates: List<ScannedGroceryItem>? = null,
    val snackbarMessage: String? = null
)

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val listsRepository: ListsRepository,
    private val settingsStore: SettingsStore,
    private val groceryImageScanner: GroceryImageScanner,
    private val imagePreprocessor: ImagePreprocessor,
    private val imageStorageManager: ImageStorageManager
) : ViewModel() {

    fun createCameraUri(): Uri = imageStorageManager.createTempCameraUri().first

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
                listsRepository.observeInventoryItems(),
                listsRepository.observeCookedFoodItems()
            ) { grocery, inventory, cooked ->
                _uiState.value.copy(
                    isLoading = false,
                    groceryItems = grocery,
                    inventoryItems = inventory,
                    cookedFoodItems = cooked
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun selectTab(tab: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    // --- Grocery ---

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

    // --- Pantry Inventory ---

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

    // --- Grocery Image Scanning ---

    fun scanGroceryImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanningGrocery = true)
            try {
                val base64List = uris.mapNotNull { uri ->
                    try {
                        imagePreprocessor.processUri(uri)?.base64
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
                if (base64List.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isScanningGrocery = false,
                        snackbarMessage = "Could not process selected image(s)."
                    )
                    return@launch
                }

                val result = groceryImageScanner.scanGroceryImages(base64List)
                result.fold(
                    onSuccess = { scan ->
                        listsRepository.logScanUsage(scan.usage)
                        val items = scan.items
                        if (items.isEmpty()) {
                            _uiState.value = _uiState.value.copy(
                                isScanningGrocery = false,
                                snackbarMessage = "No grocery items detected in the image(s)."
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(
                                isScanningGrocery = false,
                                scannedGroceryCandidates = items
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isScanningGrocery = false,
                            snackbarMessage = "Scanning failed: ${error.message ?: "Unknown error"}"
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isScanningGrocery = false,
                    snackbarMessage = "Error scanning image: ${e.message}"
                )
            }
        }
    }

    fun saveScannedGroceryItems(
        items: List<ScannedGroceryItem>,
        defaultStatus: String = InventoryItemEntity.STATUS_IN_STOCK
    ) {
        viewModelScope.launch {
            val count = listsRepository.upsertScannedInventoryItems(items, defaultStatus)
            _uiState.value = _uiState.value.copy(
                scannedGroceryCandidates = null,
                snackbarMessage = "Saved $count items to pantry inventory!"
            )
        }
    }

    fun dismissScannedGroceryDialog() {
        _uiState.value = _uiState.value.copy(scannedGroceryCandidates = null)
    }

    // --- Cooked Food / Leftovers ---

    fun addCookedFoodItem(
        name: String,
        portions: Int = 1,
        dateCooked: String = LocalDate.now().toString(),
        notes: String? = null,
        recipeId: Long? = null
    ) {
        viewModelScope.launch {
            val message = try {
                listsRepository.addCookedFoodItem(
                    name = name,
                    portions = portions,
                    dateCooked = dateCooked,
                    notes = notes,
                    recipeId = recipeId
                )
                "Added '$name' ($portions portions) to Cooked Food!"
            } catch (e: IllegalArgumentException) {
                e.message ?: "Couldn't add '$name'"
            }
            _uiState.value = _uiState.value.copy(snackbarMessage = message)
        }
    }

    fun updateCookedFoodPortions(id: Long, newPortions: Int) {
        viewModelScope.launch {
            listsRepository.updateCookedFoodPortions(id, newPortions)
        }
    }

    fun deleteCookedFoodItem(id: Long) {
        viewModelScope.launch {
            listsRepository.deleteCookedFoodItem(id)
        }
    }

    fun clearSnackbar() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }
}
