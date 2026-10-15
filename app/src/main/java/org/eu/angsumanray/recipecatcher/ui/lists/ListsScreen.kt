package org.eu.angsumanray.recipecatcher.ui.lists

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.eu.angsumanray.recipecatcher.core.llm.ScannedGroceryItem
import org.eu.angsumanray.recipecatcher.core.planner.GroceryCategories
import org.eu.angsumanray.recipecatcher.data.db.entity.CookedFoodItemEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.GroceryItemEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.InventoryItemEntity
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    viewModel: ListsViewModel
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    var showAddGroceryDialog by remember { mutableStateOf(false) }
    var showAddInventoryDialog by remember { mutableStateOf(false) }
    var showAddCookedFoodDialog by remember { mutableStateOf(false) }
    var showPhotoSourceDialog by remember { mutableStateOf(false) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.scanGroceryImages(uris)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) {
            viewModel.scanGroceryImages(listOf(uri))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lists & Pantry", fontWeight = FontWeight.Bold) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = state.selectedTab) {
                Tab(
                    selected = state.selectedTab == 0,
                    onClick = { viewModel.selectTab(0) },
                    text = { Text("Grocery List") }
                )
                Tab(
                    selected = state.selectedTab == 1,
                    onClick = { viewModel.selectTab(1) },
                    text = { Text("Pantry Staples") }
                )
                Tab(
                    selected = state.selectedTab == 2,
                    onClick = { viewModel.selectTab(2) },
                    text = { Text("Cooked Food") }
                )
            }

            when (state.selectedTab) {
                0 -> {
                    GroceryListTabContent(
                        items = state.groceryItems,
                        onBuildFromPlan = { viewModel.buildFromPlan() },
                        onToggleChecked = { id, checked -> viewModel.toggleGroceryItem(id, checked) },
                        onDeleteItem = { id -> viewModel.deleteGroceryItem(id) },
                        onClearChecked = { viewModel.clearCheckedGrocery() },
                        onOpenAddDialog = { showAddGroceryDialog = true }
                    )
                }
                1 -> {
                    InventoryTabContent(
                        items = state.inventoryItems,
                        onUpdateStatus = { id, status -> viewModel.updateInventoryStatus(id, status) },
                        onDeleteItem = { id -> viewModel.deleteInventoryItem(id) },
                        onOpenAddDialog = { showAddInventoryDialog = true },
                        onOpenScanDialog = { showPhotoSourceDialog = true }
                    )
                }
                2 -> {
                    CookedFoodTabContent(
                        items = state.cookedFoodItems,
                        onUpdatePortions = { id, portions -> viewModel.updateCookedFoodPortions(id, portions) },
                        onDeleteItem = { id -> viewModel.deleteCookedFoodItem(id) },
                        onOpenAddDialog = { showAddCookedFoodDialog = true }
                    )
                }
            }
        }
    }

    if (showAddGroceryDialog) {
        AddGroceryItemDialog(
            onDismiss = { showAddGroceryDialog = false },
            onConfirm = { name, category, qty ->
                viewModel.addManualGroceryItem(name, category, qty)
                showAddGroceryDialog = false
            }
        )
    }

    if (showAddInventoryDialog) {
        AddInventoryItemDialog(
            onDismiss = { showAddInventoryDialog = false },
            onConfirm = { name, category, status ->
                viewModel.addInventoryItem(name, category, status)
                showAddInventoryDialog = false
            }
        )
    }

    if (showAddCookedFoodDialog) {
        AddCookedFoodDialog(
            onDismiss = { showAddCookedFoodDialog = false },
            onConfirm = { name, portions, date, notes ->
                viewModel.addCookedFoodItem(name, portions, date, notes)
                showAddCookedFoodDialog = false
            }
        )
    }

    if (showPhotoSourceDialog) {
        AlertDialog(
            onDismissRequest = { showPhotoSourceDialog = false },
            title = { Text("Scan Grocery Bill or Photos") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Take a photo of a store receipt/bill, app order screenshot, or grocery bag items to add/update your consumables inventory.")
                    OutlinedButton(
                        onClick = {
                            showPhotoSourceDialog = false
                            val uri = viewModel.createCameraUri()
                            pendingCameraUri = uri
                            cameraLauncher.launch(uri)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Take photo with camera")
                    }
                    OutlinedButton(
                        onClick = {
                            showPhotoSourceDialog = false
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pick photos from gallery")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPhotoSourceDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (state.isScanningGrocery) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Analyzing Grocery Photo...") },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Text("Extracting grocery and pantry items with AI vision...")
                }
            },
            confirmButton = {}
        )
    }

    state.scannedGroceryCandidates?.let { candidates ->
        ReviewScannedGroceryDialog(
            initialItems = candidates,
            onDismiss = { viewModel.dismissScannedGroceryDialog() },
            onConfirm = { items, status ->
                viewModel.saveScannedGroceryItems(items, status)
            }
        )
    }
}

@Composable
fun GroceryListTabContent(
    items: List<GroceryItemEntity>,
    onBuildFromPlan: () -> Unit,
    onToggleChecked: (Long, Boolean) -> Unit,
    onDeleteItem: (Long) -> Unit,
    onClearChecked: () -> Unit,
    onOpenAddDialog: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Actions row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBuildFromPlan) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Build from Plan")
            }

            Row {
                FilledTonalButton(onClick = onOpenAddDialog) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add")
                }
                val hasChecked = items.any { it.checked }
                if (hasChecked) {
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = onClearChecked) {
                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Your grocery list is empty",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap 'Build from Plan' to aggregate ingredients for this week.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return
        }

        val groupedByCategory = items.groupBy { it.category }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            groupedByCategory.forEach { (category, categoryItems) ->
                item {
                    Text(
                        text = category.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(categoryItems, key = { it.id }) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (item.checked) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleChecked(item.id, !item.checked) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = item.checked,
                                onCheckedChange = { onToggleChecked(item.id, it) }
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                                    color = if (item.checked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface
                                )
                                if (!item.quantityText.isNullOrBlank()) {
                                    Text(
                                        text = item.quantityText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (item.quantityText.contains("running low")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Surface(
                                color = when (item.source) {
                                    GroceryItemEntity.SOURCE_PLAN -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    GroceryItemEntity.SOURCE_INVENTORY -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                                    else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                },
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = item.source.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }

                            IconButton(onClick = { onDeleteItem(item.id) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete item", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InventoryTabContent(
    items: List<InventoryItemEntity>,
    onUpdateStatus: (Long, String) -> Unit,
    onDeleteItem: (Long) -> Unit,
    onOpenAddDialog: () -> Unit,
    onOpenScanDialog: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Pantry (${items.size} items)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenScanDialog) {
                    Text("Scan Bill / Photo")
                }
                FilledTonalButton(onClick = onOpenAddDialog) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Pantry inventory is empty.",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Add staples or scan a grocery bill to track stock levels.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return
        }

        val groupedByStatus = listOf(
            InventoryItemEntity.STATUS_OUT to items.filter { it.status == InventoryItemEntity.STATUS_OUT },
            InventoryItemEntity.STATUS_LOW to items.filter { it.status == InventoryItemEntity.STATUS_LOW },
            InventoryItemEntity.STATUS_IN_STOCK to items.filter { it.status == InventoryItemEntity.STATUS_IN_STOCK }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            groupedByStatus.forEach { (status, statusItems) ->
                if (statusItems.isNotEmpty()) {
                    item {
                        val header = when (status) {
                            InventoryItemEntity.STATUS_OUT -> "Out of Stock (${statusItems.size})"
                            InventoryItemEntity.STATUS_LOW -> "Running Low (${statusItems.size})"
                            else -> "In Stock (${statusItems.size})"
                        }
                        Text(
                            text = header,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = when (status) {
                                InventoryItemEntity.STATUS_OUT -> MaterialTheme.colorScheme.error
                                InventoryItemEntity.STATUS_LOW -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                    }

                    items(statusItems, key = { it.id }) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = item.category.replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FilterChip(
                                        selected = item.status == InventoryItemEntity.STATUS_IN_STOCK,
                                        onClick = { onUpdateStatus(item.id, InventoryItemEntity.STATUS_IN_STOCK) },
                                        label = { Text("In", style = MaterialTheme.typography.labelSmall) }
                                    )
                                    FilterChip(
                                        selected = item.status == InventoryItemEntity.STATUS_LOW,
                                        onClick = { onUpdateStatus(item.id, InventoryItemEntity.STATUS_LOW) },
                                        label = { Text("Low", style = MaterialTheme.typography.labelSmall) }
                                    )
                                    FilterChip(
                                        selected = item.status == InventoryItemEntity.STATUS_OUT,
                                        onClick = { onUpdateStatus(item.id, InventoryItemEntity.STATUS_OUT) },
                                        label = { Text("Out", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }

                                IconButton(onClick = { onDeleteItem(item.id) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete item", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CookedFoodTabContent(
    items: List<CookedFoodItemEntity>,
    onUpdatePortions: (Long, Int) -> Unit,
    onDeleteItem: (Long) -> Unit,
    onOpenAddDialog: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Cooked Food (${items.size} dishes)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            FilledTonalButton(onClick = onOpenAddDialog) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Dish")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No cooked food or leftovers stored",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Add food brought from home, takeout leftovers, or extra cooked portions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.id }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Cooked: ${item.dateCooked}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!item.notes.isNullOrBlank()) {
                                    Text(
                                        text = "• ${item.notes}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        // Portion controls: - count +
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onUpdatePortions(item.id, item.portions - 1) },
                                modifier = Modifier.size(36.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) {
                                Text("−", fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = "${item.portions}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                            OutlinedButton(
                                onClick = { onUpdatePortions(item.id, item.portions + 1) },
                                modifier = Modifier.size(36.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) {
                                Text("+", fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(onClick = { onDeleteItem(item.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete dish", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddCookedFoodDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, portions: Int, dateCooked: String, notes: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var portions by remember { mutableIntStateOf(2) }
    var dateCooked by remember { mutableStateOf(LocalDate.now().toString()) }
    var notes by remember { mutableStateOf("") }
    val dateValid = remember(dateCooked) { runCatching { LocalDate.parse(dateCooked.trim()) }.isSuccess }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Cooked Food / Leftovers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Dish Name (e.g. Mom's Rajma, Takeout Pizza)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Portions:", style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { if (portions > 1) portions-- },
                            modifier = Modifier.size(36.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                        ) {
                            Text("−")
                        }
                        Text(
                            text = "$portions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        OutlinedButton(
                            onClick = { portions++ },
                            modifier = Modifier.size(36.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                        ) {
                            Text("+")
                        }
                    }
                }

                OutlinedTextField(
                    value = dateCooked,
                    onValueChange = { dateCooked = it },
                    label = { Text("Date (YYYY-MM-DD)") },
                    isError = !dateValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (e.g. From home, Shelf 2)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && dateValid,
                onClick = { onConfirm(name.trim(), portions, dateCooked.trim(), notes.trim().ifBlank { null }) }
            ) {
                Text("Add to Cooked Food")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ReviewScannedGroceryDialog(
    initialItems: List<ScannedGroceryItem>,
    onDismiss: () -> Unit,
    onConfirm: (List<ScannedGroceryItem>, defaultStatus: String) -> Unit
) {
    val items = remember { mutableStateListOf(*initialItems.toTypedArray()) }
    var selectedStatus by remember { mutableStateOf(InventoryItemEntity.STATUS_IN_STOCK) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review Scanned Items (${items.size})") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Edit names and categories, or remove incorrect items, before saving to inventory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Default Status:", style = MaterialTheme.typography.labelMedium)
                    FilterChip(
                        selected = selectedStatus == InventoryItemEntity.STATUS_IN_STOCK,
                        onClick = { selectedStatus = InventoryItemEntity.STATUS_IN_STOCK },
                        label = { Text("In Stock") }
                    )
                    FilterChip(
                        selected = selectedStatus == InventoryItemEntity.STATUS_LOW,
                        onClick = { selectedStatus = InventoryItemEntity.STATUS_LOW },
                        label = { Text("Low") }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(items) { index, item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    OutlinedTextField(
                                        value = item.name,
                                        onValueChange = { newName ->
                                            items[index] = item.copy(name = newName)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodyMedium
                                    )
                                    var categoryExpanded by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(onClick = { categoryExpanded = true }) {
                                            Text(
                                                text = item.category.replaceFirstChar { it.uppercase() } + (item.estimatedQuantity?.let { " • $it" } ?: ""),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                        DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                                            GroceryCategories.ALL.forEach { cat ->
                                                DropdownMenuItem(
                                                    text = { Text(cat.replaceFirstChar { it.uppercase() }) },
                                                    onClick = {
                                                        items[index] = item.copy(category = cat)
                                                        categoryExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                IconButton(onClick = { items.removeAt(index) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { items.add(ScannedGroceryItem(name = "", category = GroceryCategories.PANTRY)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Another Item")
                }
            }
        },
        confirmButton = {
            Button(
                enabled = items.any { it.name.isNotBlank() },
                onClick = { onConfirm(items.filter { it.name.isNotBlank() }, selectedStatus) }
            ) {
                Text("Save to Inventory (${items.count { it.name.isNotBlank() }})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun AddGroceryItemDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, category: String, quantity: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(GroceryCategories.OTHER) }
    var quantity by remember { mutableStateOf("") }
    var expandedCategory by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Grocery Item") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Item Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it },
                    label = { Text("Quantity (e.g., 2 lbs, 500g)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Category:", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(
                    onClick = { expandedCategory = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(category.replaceFirstChar { it.uppercase() })
                }
                DropdownMenu(expanded = expandedCategory, onDismissRequest = { expandedCategory = false }) {
                    GroceryCategories.ALL.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat.replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                category = cat
                                expandedCategory = false
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), category, quantity.trim().ifEmpty { null }) }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun AddInventoryItemDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, category: String, status: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(GroceryCategories.OTHER) }
    var status by remember { mutableStateOf(InventoryItemEntity.STATUS_IN_STOCK) }
    var expandedCategory by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Inventory Item") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Item Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Category:", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(
                    onClick = { expandedCategory = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(category.replaceFirstChar { it.uppercase() })
                }
                DropdownMenu(expanded = expandedCategory, onDismissRequest = { expandedCategory = false }) {
                    GroceryCategories.ALL.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat.replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                category = cat
                                expandedCategory = false
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Initial Status:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = status == InventoryItemEntity.STATUS_IN_STOCK,
                        onClick = { status = InventoryItemEntity.STATUS_IN_STOCK },
                        label = { Text("In Stock") }
                    )
                    FilterChip(
                        selected = status == InventoryItemEntity.STATUS_LOW,
                        onClick = { status = InventoryItemEntity.STATUS_LOW },
                        label = { Text("Low") }
                    )
                    FilterChip(
                        selected = status == InventoryItemEntity.STATUS_OUT,
                        onClick = { status = InventoryItemEntity.STATUS_OUT },
                        label = { Text("Out") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), category, status) }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
