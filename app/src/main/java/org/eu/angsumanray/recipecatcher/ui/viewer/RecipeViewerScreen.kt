package org.eu.angsumanray.recipecatcher.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.core.domain.FractionFormatter
import org.eu.angsumanray.recipecatcher.data.db.entity.CookLogEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeViewerScreen(
    viewModel: RecipeViewerViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToCookingMode: (recipeId: Long, servings: Int) -> Unit,
    onNavigateToEdit: (Long) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showReExtractDialog by remember { mutableStateOf(false) }
    var showEditNoteDialog by remember { mutableStateOf(false) }
    var noteInput by remember { mutableStateOf("") }
    var showMarkCookedDialog by rememberSaveable { mutableStateOf(false) }
    var selectedPhotoForCover by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.reExtractError) {
        state.reExtractError?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    val recipe = state.recipe

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recipe?.title ?: "Recipe", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (recipe != null) {
                        IconButton(onClick = { viewModel.toggleFavourite() }) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = if (recipe.isFavourite) "Remove favourite" else "Favourite",
                                tint = if (recipe.isFavourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (!recipe.sourceUrl.isNullOrBlank()) {
                                DropdownMenuItem(
                                    text = { Text("Open source") },
                                    onClick = {
                                        showMenu = false
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(recipe.sourceUrl))
                                        try {
                                            context.startActivity(intent)
                                        } catch (e: ActivityNotFoundException) {
                                            scope.launch { snackbarHostState.showSnackbar("No app can open this link") }
                                        }
                                    }
                                )
                            }
                            if (!recipe.captionRaw.isNullOrBlank()) {
                                DropdownMenuItem(
                                    text = { Text("Re-extract") },
                                    onClick = {
                                        showMenu = false
                                        showReExtractDialog = true
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Edit recipe") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToEdit(recipe.id)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete recipe") },
                                onClick = {
                                    showMenu = false
                                    showDeleteDialog = true
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (recipe != null) {
                Surface(
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { showMarkCookedDialog = true }
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Mark Cooked")
                        }
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigateToCookingMode(recipe.id, state.currentServings) }
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cook Mode")
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (state.isLoading || recipe == null) {
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

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Cover Image
            val imageRelPath = recipe.coverPhotoPath ?: recipe.imagePath
            if (!imageRelPath.isNullOrBlank()) {
                item {
                    val file = File(context.filesDir, imageRelPath)
                    AsyncImage(
                        model = file,
                        contentDescription = recipe.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            // Recipe Title & Description
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = recipe.title.orEmpty(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (!recipe.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = recipe.description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!recipe.authorHandle.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "by ${recipe.authorHandle}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Metadata Row & Badges
            item {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val totalTime = recipe.totalMin ?: ((recipe.prepMin ?: 0) + (recipe.cookMin ?: 0))
                    if (totalTime > 0) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("$totalTime min") }
                        )
                    }
                    if (recipe.prepMin != null && recipe.prepMin > 0) {
                        SuggestionChip(onClick = {}, label = { Text("Prep: ${recipe.prepMin}m") })
                    }
                    if (recipe.cookMin != null && recipe.cookMin > 0) {
                        SuggestionChip(onClick = {}, label = { Text("Cook: ${recipe.cookMin}m") })
                    }
                    if (recipe.kcalPerServing != null && recipe.kcalPerServing > 0) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("~${recipe.kcalPerServing} kcal (estimate)") }
                        )
                    }
                    if (!recipe.complexity.isNullOrBlank()) {
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    if (!recipe.complexityReason.isNullOrBlank()) {
                                        "${recipe.complexity.capitalize()} • ${recipe.complexityReason}"
                                    } else {
                                        recipe.complexity.capitalize()
                                    }
                                )
                            }
                        )
                    }
                    if (!recipe.spiceLevel.isNullOrBlank()) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("Spice: ${recipe.spiceLevel.capitalize()}") }
                        )
                    }
                    if (!recipe.dietType.isNullOrBlank()) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text(recipe.dietType.capitalize()) }
                        )
                    }
                    if (recipe.makeAhead) {
                        SuggestionChip(onClick = {}, label = { Text("Make-ahead") })
                    }
                    if (recipe.freezerFriendly) {
                        SuggestionChip(onClick = {}, label = { Text("Freezer-friendly") })
                    }
                    state.dietTags.forEach { dietTag ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text("Contains: ${dietTag.name}") }
                        )
                    }
                }
            }

            // Tags Chips
            if (state.tags.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = "Tags",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            state.tags.forEach { tag ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(tag.name) }
                                )
                            }
                        }
                    }
                }
            }

            // Equipment & Consumables
            if (state.equipmentTags.isNotEmpty() || state.consumableTags.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (state.equipmentTags.isNotEmpty()) {
                            Text(
                                text = "Equipment",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                state.equipmentTags.forEach { eq ->
                                    AssistChip(onClick = {}, label = { Text(eq.name) })
                                }
                            }
                        }
                        if (state.consumableTags.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Consumables",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                state.consumableTags.forEach { cons ->
                                    AssistChip(onClick = {}, label = { Text(cons.name) })
                                }
                            }
                        }
                    }
                }
            }

            // Personal Notes Section
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Personal Notes",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = {
                                noteInput = recipe.personalNote.orEmpty()
                                showEditNoteDialog = true
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit note", modifier = Modifier.size(18.dp))
                            }
                        }
                        if (!recipe.personalNote.isNullOrBlank()) {
                            Text(
                                text = recipe.personalNote,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            Text(
                                text = "No personal notes yet. Tap edit to add tips, tweaks or reminders.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Servings Stepper & Ingredients Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ingredients",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.decrementServings() },
                            modifier = Modifier.size(36.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            enabled = state.currentServings > 1
                        ) {
                            Text("-", fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = "${state.currentServings} servings",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedButton(
                            onClick = { viewModel.incrementServings() },
                            modifier = Modifier.size(36.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            enabled = state.currentServings < 50
                        ) {
                            Text("+", fontWeight = FontWeight.Bold)
                        }
                        if (state.checkedIngredientIds.isNotEmpty()) {
                            TextButton(onClick = { viewModel.clearIngredientChecks() }) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }

            // Ingredients List with Groups
            val groupedIngredients = state.scaledIngredients.groupBy { it.groupName }
            groupedIngredients.forEach { (groupName, items) ->
                if (!groupName.isNullOrBlank()) {
                    item {
                        Text(
                            text = groupName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }

                items(items, key = { "ing_${it.id}" }) { ingredient ->
                    val isChecked = state.checkedIngredientIds.contains(ingredient.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleIngredientCheck(ingredient.id) }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { viewModel.toggleIngredientCheck(ingredient.id) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val quantityText = formatIngredientQuantity(ingredient)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = buildString {
                                    if (quantityText.isNotBlank()) append("$quantityText ")
                                    append(ingredient.name)
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None
                                ),
                                color = if (isChecked) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
                            )
                            if (!ingredient.note.isNullOrBlank()) {
                                Text(
                                    text = ingredient.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Steps Section
            item {
                Text(
                    text = "Steps",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            items(state.steps, key = { "step_${it.id}" }) { step ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Step ${step.position + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (step.timerSeconds != null && step.timerSeconds > 0) {
                                val minutes = step.timerSeconds / 60
                                val seconds = step.timerSeconds % 60
                                val timerLabel = if (seconds == 0) "${minutes}m" else "${minutes}m ${seconds}s"
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("Timer: $timerLabel") }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = step.text,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Cook Log Section
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Cook Log History",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (state.timesCooked > 0) {
                            "Cooked ${state.timesCooked} time${if (state.timesCooked > 1) "s" else ""} • Last cooked: ${state.lastCookedDate ?: "Unknown"}"
                        } else {
                            "Never cooked yet"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (state.cookLogs.isNotEmpty()) {
                        val photos = state.cookLogs.filter { !it.photoPath.isNullOrBlank() }
                        if (photos.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Cook Photos (tap to view or set as cover):",
                                style = MaterialTheme.typography.titleSmall
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(photos, key = { "photo_${it.id}" }) { log ->
                                    val file = File(context.filesDir, log.photoPath!!)
                                    AsyncImage(
                                        model = file,
                                        contentDescription = "Cook photo on ${log.cookedOn}",
                                        modifier = Modifier
                                            .size(90.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { selectedPhotoForCover = log.photoPath },
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog: Delete confirmation
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Recipe?") },
            text = { Text("This will permanently delete this recipe and all associated cook photos.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteRecipe(onDeleted = onNavigateBack)
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Re-extract confirmation
    if (showReExtractDialog) {
        AlertDialog(
            onDismissRequest = { showReExtractDialog = false },
            title = { Text("Re-extract Recipe?") },
            text = { Text("Re-extracting will re-parse the original caption with Claude Haiku 5.5. Your favourites, personal notes, cover photo, and cook log will be preserved.") },
            confirmButton = {
                Button(
                    onClick = {
                        showReExtractDialog = false
                        viewModel.reExtract(onComplete = {})
                    }
                ) {
                    Text("Re-extract")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReExtractDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Edit personal note
    if (showEditNoteDialog) {
        AlertDialog(
            onDismissRequest = { showEditNoteDialog = false },
            title = { Text("Personal Notes") },
            text = {
                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    label = { Text("Notes & tips") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updatePersonalNote(noteInput)
                        showEditNoteDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNoteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Photo Detail & Set as Cover
    if (selectedPhotoForCover != null) {
        val photoPath = selectedPhotoForCover!!
        val file = File(context.filesDir, photoPath)
        AlertDialog(
            onDismissRequest = { selectedPhotoForCover = null },
            title = { Text("Cook Photo") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = file,
                        contentDescription = "Cook Photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Would you like to set this photo as the recipe's cover image?")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setAsCoverPhoto(photoPath)
                        selectedPhotoForCover = null
                    }
                ) {
                    Text("Set as Cover")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPhotoForCover = null }) {
                    Text("Close")
                }
            }
        )
    }

    // Dialog: Mark as Cooked
    if (showMarkCookedDialog) {
        MarkCookedDialog(
            onDismiss = { showMarkCookedDialog = false },
            onConfirm = { date, note, photoUri ->
                viewModel.markAsCooked(date, note, photoUri)
                showMarkCookedDialog = false
            },
            onCreateTempCameraUri = { viewModel.createTempCameraUri() }
        )
    }
}

@Composable
private fun MarkCookedDialog(
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, String?, Uri?) -> Unit,
    onCreateTempCameraUri: () -> Pair<Uri, File>
) {
    // Saveable so a camera round trip that recreates the activity keeps the dialog's input.
    var note by rememberSaveable { mutableStateOf("") }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now()) }
    var photoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var tempCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            photoUri = uri
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success && tempCameraUri != null) {
            photoUri = tempCameraUri
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark as Cooked") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Date: $selectedDate",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Notes (e.g. less chilli next time)") },
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
                    AsyncImage(
                        model = photoUri,
                        contentDescription = "Selected Photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        selectedDate,
                        note.trim().ifEmpty { null },
                        photoUri
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatIngredientQuantity(ingredient: IngredientEntity): String =
    FractionFormatter.format(ingredient.quantity, ingredient.quantityMax, ingredient.unit)
