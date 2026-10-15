package org.eu.angsumanray.recipecatcher.ui.edit

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import org.eu.angsumanray.recipecatcher.core.llm.WebRecipeSuggestion
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditRecipeScreen(
    viewModel: EditRecipeViewModel,
    onNavigateBack: () -> Unit,
    onSaveSuccess: (Long) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showPasteDialog by remember { mutableStateOf(false) }
    var showFindWithAiDialog by rememberSaveable { mutableStateOf(false) }
    var pendingCandidate by remember { mutableStateOf<WebRecipeSuggestion?>(null) }
    var pasteInput by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showPhotoSourceDialog by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.fillFromPhotos(uris) {}
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            pendingCameraUri?.let { uri ->
                viewModel.fillFromPhotos(listOf(uri)) {}
            }
        }
    }

    LaunchedEffect(state.extractError) {
        state.extractError?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNewRecipe) "Write Recipe" else "Edit Recipe") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showFindWithAiDialog = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Find with AI")
                    }
                    IconButton(onClick = { showPasteDialog = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Fill from pasted text")
                    }
                    if (!state.isNewRecipe) {
                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete recipe")
                        }
                    }
                    IconButton(onClick = { viewModel.saveRecipe(onSaveSuccess) }) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 4.dp, shadowElevation = 8.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.saveRecipe(onSaveSuccess) }
                    ) {
                        Text(if (state.isNewRecipe) "Save Recipe" else "Update Recipe")
                    }
                }
            }
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Quick AI input actions: Scan photos or Paste text
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showPhotoSourceDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Scan or pick photos")
                }
                OutlinedButton(
                    onClick = { showPasteDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Fill from text")
                }
            }

            // Basic Details
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Basic Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    OutlinedTextField(
                        value = state.title,
                        onValueChange = { viewModel.updateTitle(it) },
                        label = { Text("Title *") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = state.description,
                        onValueChange = { viewModel.updateDescription(it) },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.servingsText,
                            onValueChange = { viewModel.updateServings(it) },
                            label = { Text("Servings") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.cuisine,
                            onValueChange = { viewModel.updateCuisine(it) },
                            label = { Text("Cuisine") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = if (state.prepMin > 0) state.prepMin.toString() else "",
                            onValueChange = { viewModel.updateTimes(it.toIntOrNull() ?: 0, state.cookMin, state.totalMin) },
                            label = { Text("Prep (min)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = if (state.cookMin > 0) state.cookMin.toString() else "",
                            onValueChange = { viewModel.updateTimes(state.prepMin, it.toIntOrNull() ?: 0, state.totalMin) },
                            label = { Text("Cook (min)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = if (state.totalMin > 0) state.totalMin.toString() else "",
                            onValueChange = { viewModel.updateTimes(state.prepMin, state.cookMin, it.toIntOrNull() ?: 0) },
                            label = { Text("Total (min)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Attributes: Complexity, Spice, Diet, Calories
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Attributes & Classification", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    // Complexity
                    Text("Complexity", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("easy", "medium", "hard").forEach { level ->
                            FilterChip(
                                selected = state.complexity.equals(level, ignoreCase = true),
                                onClick = { viewModel.updateComplexity(if (state.complexity == level) "" else level) },
                                label = { Text(level.replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = state.complexityReason,
                        onValueChange = { viewModel.updateComplexity(state.complexity, it) },
                        label = { Text("Complexity Reason") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Spice Level
                    Text("Spice Level", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("mild", "medium", "hot").forEach { spice ->
                            FilterChip(
                                selected = state.spiceLevel.equals(spice, ignoreCase = true),
                                onClick = { viewModel.updateSpiceLevel(if (state.spiceLevel == spice) "" else spice) },
                                label = { Text(spice.replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }

                    // Diet Type
                    Text("Diet Type", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("vegan", "vegetarian", "eggetarian", "non-veg").forEach { diet ->
                            FilterChip(
                                selected = state.dietType.equals(diet, ignoreCase = true),
                                onClick = { viewModel.updateDietType(if (state.dietType == diet) "" else diet) },
                                label = { Text(diet.replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }

                    // Calories
                    OutlinedTextField(
                        value = if (state.kcalPerServing > 0) state.kcalPerServing.toString() else "",
                        onValueChange = { viewModel.updateCalories(it.toIntOrNull() ?: 0) },
                        label = { Text("Calories per Serving (kcal estimate)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Switches for Make-ahead and Freezer-friendly
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Make-ahead friendly")
                        Switch(checked = state.makeAhead, onCheckedChange = { viewModel.toggleMakeAhead(it) })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Freezer-friendly")
                        Switch(checked = state.freezerFriendly, onCheckedChange = { viewModel.toggleFreezerFriendly(it) })
                    }
                }
            }

            // Tags Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Tags", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.allTags.forEach { tag ->
                            val selected = state.selectedTagIds.contains(tag.id)
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.toggleTag(tag.id) },
                                label = { Text(tag.name) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Contains (Allergens & Diet)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.allDiet.forEach { tag ->
                            val selected = state.selectedTagIds.contains(tag.id)
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.toggleTag(tag.id) },
                                label = { Text(tag.name) }
                            )
                        }
                    }

                    if (state.allEquipment.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Equipment", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.allEquipment.forEach { tag ->
                                val selected = state.selectedTagIds.contains(tag.id)
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.toggleTag(tag.id) },
                                    label = { Text(tag.name) }
                                )
                            }
                        }
                    }

                    if (state.allConsumables.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Consumables", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.allConsumables.forEach { tag ->
                                val selected = state.selectedTagIds.contains(tag.id)
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.toggleTag(tag.id) },
                                    label = { Text(tag.name) }
                                )
                            }
                        }
                    }
                }
            }

            // Personal Notes
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Personal Notes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.personalNote,
                        onValueChange = { viewModel.updatePersonalNote(it) },
                        label = { Text("Your tweaks, variations and tips") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6
                    )
                }
            }

            // Ingredients List
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Ingredients (${state.ingredients.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { viewModel.addIngredient() }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add")
                    }
                }

                state.ingredients.forEachIndexed { index, ing ->
                    val quantityText = state.quantityTexts.getOrElse(index) { "" }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Ingredient ${index + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                if (state.ingredients.size > 1) {
                                    IconButton(
                                        onClick = { viewModel.removeIngredient(index) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = ing.name,
                                onValueChange = {
                                    viewModel.updateIngredient(index, it, quantityText, ing.unit, ing.note, ing.groupName)
                                },
                                label = { Text("Name *") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = quantityText,
                                    onValueChange = {
                                        viewModel.updateIngredient(index, ing.name, it, ing.unit, ing.note, ing.groupName)
                                    },
                                    label = { Text("Qty") },
                                    placeholder = { Text("2–3") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = ing.unit.orEmpty(),
                                    onValueChange = {
                                        viewModel.updateIngredient(index, ing.name, quantityText, it, ing.note, ing.groupName)
                                    },
                                    label = { Text("Unit") },
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = ing.groupName.orEmpty(),
                                    onValueChange = {
                                        viewModel.updateIngredient(index, ing.name, quantityText, ing.unit, ing.note, it)
                                    },
                                    label = { Text("Group") },
                                    modifier = Modifier.weight(1.5f)
                                )
                            }

                            OutlinedTextField(
                                value = ing.note.orEmpty(),
                                onValueChange = {
                                    viewModel.updateIngredient(index, ing.name, quantityText, ing.unit, it, ing.groupName)
                                },
                                label = { Text("Note (e.g. finely chopped)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Steps List
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Steps (${state.steps.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { viewModel.addStep() }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add")
                    }
                }

                state.steps.forEachIndexed { index, step ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Step ${index + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                if (state.steps.size > 1) {
                                    IconButton(
                                        onClick = { viewModel.removeStep(index) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = step.text,
                                onValueChange = { viewModel.updateStep(index, it, step.timerSeconds) },
                                label = { Text("Instruction *") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 5
                            )

                            OutlinedTextField(
                                value = step.timerSeconds?.toString() ?: "",
                                onValueChange = { viewModel.updateStep(index, step.text, it.toIntOrNull()) },
                                label = { Text("Timer Seconds (optional)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Dialog: Fill from pasted text
    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.isExtractingText) showPasteDialog = false },
            title = { Text("Fill from Pasted Text") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Paste any recipe text (from WhatsApp, family notes, website, etc.) and Claude Haiku 5.5 will populate the form fields.")
                    OutlinedTextField(
                        value = pasteInput,
                        onValueChange = { pasteInput = it },
                        label = { Text("Paste recipe here") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8,
                        enabled = !state.isExtractingText
                    )
                    if (state.isExtractingText) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text("Extracting recipe with AI...")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.fillFromPastedText(pasteInput) {
                            showPasteDialog = false
                            pasteInput = ""
                        }
                    },
                    enabled = pasteInput.isNotBlank() && !state.isExtractingText
                ) {
                    Text("Extract")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPasteDialog = false },
                    enabled = !state.isExtractingText
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Scan or pick photos
    if (showPhotoSourceDialog) {
        AlertDialog(
            onDismissRequest = { showPhotoSourceDialog = false },
            title = { Text("Scan or Pick Photos") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Take a photo of a recipe book, handwritten card, or note, or select up to 5 photos from your gallery to extract with AI vision.")
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
                TextButton(onClick = { showPhotoSourceDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (state.isExtractingText && !showPasteDialog && !showFindWithAiDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Analyzing Photos") },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("Extracting recipe details with Claude vision...")
                }
            },
            confirmButton = {}
        )
    }

    // Dialog: Find with AI
    if (showFindWithAiDialog) {
        FindWithAiDialog(
            state = state,
            onDismiss = {
                showFindWithAiDialog = false
                viewModel.clearAiCandidates()
            },
            onSearch = { dish, hints ->
                viewModel.searchFindWithAi(dish, hints)
            },
            onSelectCandidate = { candidate ->
                if (EditRecipeViewModel.formHasContent(state)) {
                    pendingCandidate = candidate
                } else {
                    viewModel.applyAiCandidate(candidate, replaceExisting = true) {
                        showFindWithAiDialog = false
                    }
                }
            },
            onFillMissingMetadata = {
                viewModel.fillMissingMetadataWithAi {
                    showFindWithAiDialog = false
                }
            }
        )
    }

    // Dialog: the form already has content, so ask before Find with AI touches it
    pendingCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { pendingCandidate = null },
            title = { Text("Use \"${candidate.title}\"?") },
            text = { Text("This form already has content. Fill only the empty fields, or replace everything with the found recipe?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingCandidate = null
                    viewModel.applyAiCandidate(candidate, replaceExisting = false) { showFindWithAiDialog = false }
                }) { Text("Fill empty") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingCandidate = null }) { Text("Cancel") }
                    TextButton(onClick = {
                        pendingCandidate = null
                        viewModel.applyAiCandidate(candidate, replaceExisting = true) { showFindWithAiDialog = false }
                    }) { Text("Replace") }
                }
            }
        )
    }

    // Dialog: Delete confirmation
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Recipe?") },
            text = { Text("This will permanently delete this recipe.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteRecipe(onDeleted = onNavigateBack)
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun FindWithAiDialog(
    state: EditRecipeUiState,
    onDismiss: () -> Unit,
    onSearch: (dishName: String, hints: String) -> Unit,
    onSelectCandidate: (WebRecipeSuggestion) -> Unit,
    onFillMissingMetadata: () -> Unit
) {
    var dishNameInput by rememberSaveable { mutableStateOf(state.title) }
    var hintsInput by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Find with AI", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Search online recipes by dish name, or auto-fill metadata for this recipe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = dishNameInput,
                    onValueChange = { dishNameInput = it },
                    label = { Text("Dish name") },
                    placeholder = { Text("e.g. Palak Paneer, Tacos") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = hintsInput,
                    onValueChange = { hintsInput = it },
                    label = { Text("Hints (optional)") },
                    placeholder = { Text("e.g. Bengali style, no onion") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { onSearch(dishNameInput, hintsInput) },
                        enabled = dishNameInput.isNotBlank() && !state.isSearchingAi && !state.isFetchingCandidate
                    ) {
                        Text("Search Web")
                    }

                    if (state.title.isNotBlank()) {
                        TextButton(
                            onClick = onFillMissingMetadata,
                            enabled = !state.isExtractingText && !state.isSearchingAi
                        ) {
                            Text("Fill Details")
                        }
                    }
                }

                if (state.isSearchingAi || state.isFetchingCandidate) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            if (state.isFetchingCandidate) "Fetching & extracting recipe..." else "Searching for recipes...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                state.aiSearchError?.let { err ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (state.aiCandidates.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Found Candidates:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    for (candidate in state.aiCandidates) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = candidate.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = candidate.site.ifBlank { "Web Recipe" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (candidate.reason.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = candidate.reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { onSelectCandidate(candidate) },
                                    enabled = !state.isFetchingCandidate,
                                    modifier = Modifier.align(Alignment.End),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("Use This Recipe", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.isFetchingCandidate
            ) {
                Text("Close")
            }
        }
    )
}
