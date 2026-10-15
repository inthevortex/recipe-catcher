package org.eu.angsumanray.recipecatcher.ui.pantry

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.eu.angsumanray.recipecatcher.core.model.Ingredient
import org.eu.angsumanray.recipecatcher.core.model.InventoryItem
import org.eu.angsumanray.recipecatcher.core.model.InventoryStatus
import org.eu.angsumanray.recipecatcher.core.planner.PantryAction
import org.eu.angsumanray.recipecatcher.core.planner.PantryReconciliationItem
import org.eu.angsumanray.recipecatcher.core.planner.PantryReconciler
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.InventoryItemEntity
import java.io.File
import java.time.LocalDate
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryReconciliationSheet(
    recipeTitle: String,
    ingredients: List<IngredientEntity>,
    inventoryItems: List<InventoryItemEntity>,
    isSaving: Boolean = false,
    initialDate: LocalDate = LocalDate.now(),
    onCreateTempCameraUri: () -> Pair<Uri, File>,
    onDismiss: () -> Unit,
    onConfirm: (
        date: LocalDate,
        note: String?,
        photoUri: Uri?,
        reconciliationItems: List<PantryReconciliationItem>
    ) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedDate by rememberSaveable { mutableStateOf(initialDate) }
    var note by rememberSaveable { mutableStateOf("") }
    var photoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var tempCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) photoUri = uri
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success && tempCameraUri != null) {
            photoUri = tempCameraUri
        }
    }

    // Initialize reconciled items from core PantryReconciler
    var reconciliationItems by remember(ingredients, inventoryItems) {
        val coreIngredients = ingredients.map { ing ->
            Ingredient(
                name = ing.name,
                quantity = ing.quantity,
                quantityMax = ing.quantityMax,
                unit = ing.unit,
                baseName = ing.baseName ?: ing.name.trim().lowercase(Locale.ROOT),
                category = ing.category ?: "other",
                groupName = ing.groupName
            )
        }
        val coreInventory = inventoryItems.map { inv ->
            InventoryItem(
                id = inv.id,
                name = inv.name,
                baseName = inv.baseName,
                category = inv.category,
                status = inv.status,
                updatedAt = inv.updatedAt
            )
        }
        mutableStateOf(PantryReconciler.matchIngredients(coreIngredients, coreInventory))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            item {
                Column {
                    Text(
                        text = "Mark as Cooked",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (recipeTitle.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = recipeTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Date & Notes
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Cook Date: $selectedDate",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Cook note (e.g. extra spices, cook time tips)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val (uri, _) = onCreateTempCameraUri()
                                tempCameraUri = uri
                                cameraLauncher.launch(uri)
                            }
                        ) {
                            Text("Take Photo")
                        }
                        OutlinedButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        ) {
                            Text("Pick Photo")
                        }
                    }

                    if (photoUri != null) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            AsyncImage(
                                model = photoUri,
                                contentDescription = "Selected Photo",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { photoUri = null },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove photo",
                                        modifier = Modifier.padding(4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Pantry Reconciliation Header
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Pantry Reconciliation",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Update your pantry inventory for ingredients used in this recipe. Items marked Out are automatically queued on your grocery list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Ingredient reconciliation items
            if (reconciliationItems.isEmpty()) {
                item {
                    Text(
                        text = "No ingredients detected for pantry matching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                itemsIndexed(reconciliationItems, key = { _, item -> item.baseName }) { index, item ->
                    PantryReconciliationCard(
                        item = item,
                        onActionChanged = { newAction ->
                            reconciliationItems = reconciliationItems.mapIndexed { idx, itm ->
                                if (idx == index) itm.copy(action = newAction) else itm
                            }
                        }
                    )
                }
            }

            // Bottom Actions
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            onConfirm(
                                selectedDate,
                                note.trim().ifEmpty { null },
                                photoUri,
                                reconciliationItems
                            )
                        },
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("Save & Update")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PantryReconciliationCard(
    item: PantryReconciliationItem,
    onActionChanged: (PantryAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.ingredientName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )

                // Current inventory badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = when (item.initialStatus) {
                        InventoryStatus.IN_STOCK -> MaterialTheme.colorScheme.primaryContainer
                        InventoryStatus.LOW -> MaterialTheme.colorScheme.tertiaryContainer
                        InventoryStatus.OUT -> MaterialTheme.colorScheme.errorContainer
                        null -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    }
                ) {
                    val statusText = when (item.initialStatus) {
                        InventoryStatus.IN_STOCK -> "In stock"
                        InventoryStatus.LOW -> "Low"
                        InventoryStatus.OUT -> "Out"
                        null -> "Not in pantry"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = when (item.initialStatus) {
                            InventoryStatus.IN_STOCK -> MaterialTheme.colorScheme.onPrimaryContainer
                            InventoryStatus.LOW -> MaterialTheme.colorScheme.onTertiaryContainer
                            InventoryStatus.OUT -> MaterialTheme.colorScheme.onErrorContainer
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            // Per-ingredient selector: In Stock, Low, Out, Don't Change
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PantryActionChip(
                    label = "Don't Change",
                    isSelected = item.action == PantryAction.DONT_CHANGE,
                    onClick = { onActionChanged(PantryAction.DONT_CHANGE) }
                )
                PantryActionChip(
                    label = "In Stock",
                    isSelected = item.action == PantryAction.IN_STOCK,
                    onClick = { onActionChanged(PantryAction.IN_STOCK) }
                )
                PantryActionChip(
                    label = "Low",
                    isSelected = item.action == PantryAction.LOW,
                    onClick = { onActionChanged(PantryAction.LOW) }
                )
                PantryActionChip(
                    label = "Out",
                    isSelected = item.action == PantryAction.OUT,
                    onClick = { onActionChanged(PantryAction.OUT) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PantryActionChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = when (label) {
                "Out" -> MaterialTheme.colorScheme.errorContainer
                "Low" -> MaterialTheme.colorScheme.tertiaryContainer
                "In Stock" -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
            selectedLabelColor = when (label) {
                "Out" -> MaterialTheme.colorScheme.onErrorContainer
                "Low" -> MaterialTheme.colorScheme.onTertiaryContainer
                "In Stock" -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSecondaryContainer
            }
        )
    )
}
