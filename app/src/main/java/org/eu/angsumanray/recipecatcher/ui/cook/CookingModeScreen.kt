package org.eu.angsumanray.recipecatcher.ui.cook

import android.app.Activity
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.eu.angsumanray.recipecatcher.core.domain.FractionFormatter
import java.io.File
import java.time.LocalDate

import org.eu.angsumanray.recipecatcher.ui.pantry.PantryReconciliationSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookingModeScreen(
    viewModel: CookingModeViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val inventoryItems by viewModel.inventoryItems.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    // Keep screen on while cooking mode is active
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var showIngredientsSheet by rememberSaveable { mutableStateOf(false) }
    var showMarkCookedDialog by rememberSaveable { mutableStateOf(false) }

    val steps = state.steps
    val currentStepIndex = state.currentStepIndex
    val currentStep = steps.getOrNull(currentStepIndex)
    val totalSteps = steps.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (totalSteps > 0) "Step ${currentStepIndex + 1} of $totalSteps" else "Cooking Mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.Close, contentDescription = "Exit Cooking Mode")
                    }
                },
                actions = {
                    IconButton(onClick = { showIngredientsSheet = true }) {
                        Icon(Icons.Default.List, contentDescription = "Ingredients")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { viewModel.previousStep() },
                        enabled = currentStepIndex > 0
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Prev")
                    }

                    FilledTonalButton(onClick = { showIngredientsSheet = true }) {
                        Text("Ingredients")
                    }

                    if (currentStepIndex < totalSteps - 1) {
                        Button(onClick = { viewModel.nextStep() }) {
                            Text("Next")
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    } else {
                        Button(
                            onClick = { showMarkCookedDialog = true },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Done!")
                        }
                    }
                }
            }
        }
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

        if (currentStep == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
            ) {
                Text(
                    text = "This recipe has no steps.",
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp),
                    textAlign = TextAlign.Center
                )
                Button(onClick = { showMarkCookedDialog = true }) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Mark as Cooked")
                }
            }
            return@Scaffold
        }

        var dragOffset by remember { mutableStateOf(0f) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        dragOffset += delta
                    },
                    onDragStopped = {
                        if (dragOffset < -50f && currentStepIndex < totalSteps - 1) {
                            viewModel.nextStep()
                        } else if (dragOffset > 50f && currentStepIndex > 0) {
                            viewModel.previousStep()
                        }
                        dragOffset = 0f
                    }
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Step header
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "STEP ${currentStepIndex + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "${currentStepIndex + 1} / $totalSteps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Step Text - Large Text (>= 24 sp), high contrast
            Text(
                text = currentStep.text,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 26.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )

            // Step Countdown Timer (if configured for this step)
            val timerState = state.timers[currentStepIndex]
            if (timerState != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (timerState.remainingSeconds == 0) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val minutes = timerState.remainingSeconds / 60
                        val seconds = timerState.remainingSeconds % 60
                        val formattedTime = String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)

                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (timerState.remainingSeconds == 0) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            }
                        )

                        if (timerState.remainingSeconds == 0) {
                            Text(
                                text = "Timer Done!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!timerState.isRunning) {
                                Button(
                                    onClick = { viewModel.startTimer(currentStepIndex) },
                                    enabled = timerState.remainingSeconds > 0
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Start")
                                }
                            } else {
                                Button(onClick = { viewModel.pauseTimer(currentStepIndex) }) {
                                    Text("Pause")
                                }
                            }

                            OutlinedButton(onClick = { viewModel.resetTimer(currentStepIndex) }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reset")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reset")
                            }
                        }
                    }
                }
            }

            // If on the final step, show prominent "Mark as Cooked" button
            if (currentStepIndex == totalSteps - 1) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { showMarkCookedDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Recipe Complete • Mark as Cooked",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Modal Bottom Sheet: Scaled Ingredients Checklist
    if (showIngredientsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showIngredientsSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Ingredients (${state.ingredients.size}) · ${state.servings} servings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.ingredients.forEachIndexed { index, ingredient ->
                        val group = ingredient.groupName
                        if (!group.isNullOrBlank() && group != state.ingredients.getOrNull(index - 1)?.groupName) {
                            item(key = "cook_group_$index") {
                                Text(
                                    text = group,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                        item(key = "cook_ing_${ingredient.id}") {
                            val isChecked = state.checkedIngredientIds.contains(ingredient.id)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleIngredientCheck(ingredient.id) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { viewModel.toggleIngredientCheck(ingredient.id) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val qText = FractionFormatter.format(ingredient.quantity, ingredient.quantityMax, ingredient.unit)
                                Text(
                                    text = buildString {
                                        if (qText.isNotBlank()) append("$qText ")
                                        append(ingredient.name)
                                        if (!ingredient.note.isNullOrBlank()) append(" (${ingredient.note})")
                                    },
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = 18.sp,
                                        textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None
                                    ),
                                    color = if (isChecked) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    // Mark as Cooked & Reconcile Pantry
    if (showMarkCookedDialog && state.recipe != null) {
        PantryReconciliationSheet(
            recipeTitle = state.recipe?.title.orEmpty(),
            ingredients = state.rawIngredients,
            inventoryItems = inventoryItems,
            isSaving = state.isSaving,
            onCreateTempCameraUri = { viewModel.createTempCameraUri() },
            onDismiss = { showMarkCookedDialog = false },
            onConfirm = { date, note, photoUri, reconciliationItems ->
                viewModel.markAsCooked(date, note, photoUri, reconciliationItems) {
                    showMarkCookedDialog = false
                    onNavigateBack()
                }
            }
        )
    }
}
