package org.eu.angsumanray.recipecatcher.ui.library

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.File
import org.eu.angsumanray.recipecatcher.capture.InstagramPostViewerActivity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.repo.RecipeFilter
import org.eu.angsumanray.recipecatcher.data.repo.RecipeSortOrder
import org.eu.angsumanray.recipecatcher.ui.theme.AppIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onNavigateToPasteLink: () -> Unit,
    onNavigateToWriteRecipe: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToViewer: (Long) -> Unit,
    onNavigateToEdit: (Long) -> Unit,
    onNavigateToReview: (Long) -> Unit,
    onNavigateToChat: () -> Unit = {}
) {
    val filter by viewModel.filter.collectAsState()
    val savedRecipes by viewModel.savedRecipes.collectAsState()
    val pendingRecipes by viewModel.pendingRecipes.collectAsState()
    val isPendingExpanded by viewModel.isPendingExpanded.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val allDiet by viewModel.allDiet.collectAsState()
    val allEquipment by viewModel.allEquipment.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val tagGroups by viewModel.tagGroups.collectAsState()

    val context = LocalContext.current
    var showFabMenu by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var recipeToDelete by remember { mutableStateOf<RecipeEntity?>(null) }
    var recipeForPastingText by remember { mutableStateOf<RecipeEntity?>(null) }
    var pastedContentText by remember { mutableStateOf("") }

    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val newRecipesSinceBackup by viewModel.newRecipesSinceBackup.collectAsState()
    val backupReminderThreshold by viewModel.backupReminderThreshold.collectAsState()

    androidx.compose.runtime.LaunchedEffect(newRecipesSinceBackup, backupReminderThreshold) {
        if (backupReminderThreshold > 0 && newRecipesSinceBackup >= backupReminderThreshold) {
            val result = snackbarHostState.showSnackbar(
                message = "You have $newRecipesSinceBackup new recipes since last backup.",
                actionLabel = "Back up",
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                onNavigateToSettings()
            }
        }
    }

    if (recipeForPastingText != null) {
        AlertDialog(
            onDismissRequest = {
                recipeForPastingText = null
                pastedContentText = ""
            },
            title = {
                Text(
                    when (recipeForPastingText?.sourceType) {
                        "web" -> "Paste Page Text"
                        "instagram" -> "Paste Comment or Link"
                        else -> "Paste Text"
                    }
                )
            },
            text = {
                Column {
                    Text(
                        text = if (recipeForPastingText?.sourceType == "instagram") {
                            "Paste the recipe comment, an Instagram comment link (from 'Copy link'), or any recipe text. Claude will extract ingredients, instructions, and details automatically."
                        } else {
                            "Paste recipe text or HTML directly from the page:"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pastedContentText,
                        onValueChange = { pastedContentText = it },
                        label = {
                            Text(if (recipeForPastingText?.sourceType == "instagram") "Comment text or Instagram link" else "Recipe text / HTML")
                        },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        maxLines = 10
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = recipeForPastingText
                        if (target != null && pastedContentText.isNotBlank()) {
                            viewModel.pastePageText(target.id, pastedContentText.trim())
                        }
                        recipeForPastingText = null
                        pastedContentText = ""
                    },
                    enabled = pastedContentText.isNotBlank()
                ) {
                    Text("Extract Recipe")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        recipeForPastingText = null
                        pastedContentText = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Recipe Catcher", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onNavigateToChat) {
                        Icon(AppIcons.Chat, contentDescription = "Recipe Chat")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { showFabMenu = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add recipe")
                }
                DropdownMenu(
                    expanded = showFabMenu,
                    onDismissRequest = { showFabMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Paste link") },
                        onClick = {
                            showFabMenu = false
                            onNavigateToPasteLink()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Write recipe") },
                        onClick = {
                            showFabMenu = false
                            onNavigateToWriteRecipe()
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LibrarySearchField(
                query = filter.query,
                onQueryChange = { viewModel.updateSearchQuery(it) }
            )

            LibraryToolbar(
                filter = filter,
                viewMode = viewMode,
                allTags = allTags,
                allDiet = allDiet,
                allEquipment = allEquipment,
                onViewModeSelected = { viewModel.setViewMode(it) },
                onOpenFilterSheet = { showFilterSheet = true },
                onSortSelected = { viewModel.setSortOrder(it) },
                onFavouriteToggled = { viewModel.toggleFavouriteFilter() },
                onDietSelected = { viewModel.setDietTypeFilter(it) },
                onWithoutDietToggled = { viewModel.toggleWithoutDietFilter(it) },
                onNeverCookedToggled = { viewModel.toggleNeverCookedFilter() },
                onComplexitySelected = { viewModel.setComplexityFilter(it) },
                onEquipmentToggled = { viewModel.toggleEquipmentFilter(it) },
                onSpiceSelected = { viewModel.setSpiceLevelFilter(it) },
                onMakeAheadToggled = { viewModel.toggleMakeAheadFilter() },
                onFreezerToggled = { viewModel.toggleFreezerFriendlyFilter() },
                onToReviewToggled = { viewModel.toggleToReviewFilter() },
                onTagToggled = { viewModel.toggleTagFilter(it) },
                onClearAll = { viewModel.clearAllFilters() }
            )

            if (viewMode == LibraryViewMode.BY_TAG) {
                // Group by Tags View: Vertical list of tag groups with horizontal recipe carousels
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Pending Section
                    if (pendingRecipes.isNotEmpty()) {
                        item {
                            PendingSectionCard(
                                pendingRecipes = pendingRecipes,
                                isPendingExpanded = isPendingExpanded,
                                onToggleExpanded = { viewModel.togglePendingExpanded() },
                                onReview = { onNavigateToReview(it) },
                                onRetry = { viewModel.retryCapture(it) },
                                onDelete = { viewModel.deleteRecipe(it) },
                                onPastePageText = {
                                    recipeForPastingText = it
                                    pastedContentText = ""
                                },
                                onBrowsePost = { pending ->
                                    val url = pending.sourceUrl ?: (pending.sourceKey?.let { "https://www.instagram.com/p/$it/" })
                                    if (!url.isNullOrBlank()) {
                                        context.startActivity(InstagramPostViewerActivity.createIntent(context, pending.id, url))
                                    }
                                }
                            )
                        }
                    }

                    if (tagGroups.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = if (filter.isActive) "No tagged recipes match filters" else "No tagged recipes yet",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    if (filter.isActive) {
                                        OutlinedButton(onClick = { viewModel.clearAllFilters() }) {
                                            Text("Clear Filters")
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        items(tagGroups, key = { it.tagId ?: -1L }) { group ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = group.tagName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        ) {
                                            Text("${group.recipes.size}")
                                        }
                                    }

                                    if (group.tagId != null) {
                                        TextButton(
                                            onClick = {
                                                viewModel.clearAllFilters()
                                                viewModel.toggleTagFilter(group.tagId)
                                                viewModel.setViewMode(LibraryViewMode.ALL)
                                            }
                                        ) {
                                            Text("View in Grid")
                                        }
                                    }
                                }

                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(group.recipes, key = { "${group.tagId ?: 0}_${it.id}" }) { recipe ->
                                        RecipeCompactCard(
                                            recipe = recipe,
                                            onClick = { onNavigateToViewer(recipe.id) },
                                            onToggleFavourite = { viewModel.toggleRecipeFavourite(recipe) },
                                            onEdit = { onNavigateToEdit(recipe.id) },
                                            onShare = {
                                                if (!recipe.sourceUrl.isNullOrBlank()) {
                                                    val sendIntent = Intent().apply {
                                                        action = Intent.ACTION_SEND
                                                        putExtra(Intent.EXTRA_TEXT, recipe.sourceUrl)
                                                        type = "text/plain"
                                                    }
                                                    context.startActivity(Intent.createChooser(sendIntent, "Share recipe link"))
                                                }
                                            },
                                            onDelete = { recipeToDelete = recipe }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Content Grid (Pending Section + 2-Column Saved Recipe Grid)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Pending Section
                    if (pendingRecipes.isNotEmpty()) {
                        item(span = { GridItemSpan(2) }) {
                            PendingSectionCard(
                                pendingRecipes = pendingRecipes,
                                isPendingExpanded = isPendingExpanded,
                                onToggleExpanded = { viewModel.togglePendingExpanded() },
                                onReview = { onNavigateToReview(it) },
                                onRetry = { viewModel.retryCapture(it) },
                                onDelete = { viewModel.deleteRecipe(it) },
                                onPastePageText = {
                                    recipeForPastingText = it
                                    pastedContentText = ""
                                },
                                onBrowsePost = { pending ->
                                    val url = pending.sourceUrl ?: (pending.sourceKey?.let { "https://www.instagram.com/p/$it/" })
                                    if (!url.isNullOrBlank()) {
                                        context.startActivity(InstagramPostViewerActivity.createIntent(context, pending.id, url))
                                    }
                                }
                            )
                        }
                    }

                    // Saved Recipes Header
                    item(span = { GridItemSpan(2) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (filter.isActive) "Filtered Recipes (${savedRecipes.size})" else "All Saved Recipes (${savedRecipes.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (filter.isActive) {
                                TextButton(onClick = { viewModel.clearAllFilters() }) {
                                    Text("Reset")
                                }
                            }
                        }
                    }

                    // Empty State
                    if (savedRecipes.isEmpty()) {
                        item(span = { GridItemSpan(2) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = if (filter.isActive) "No recipes match the selected filters" else "No recipes saved yet",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    if (filter.isActive) {
                                        OutlinedButton(onClick = { viewModel.clearAllFilters() }) {
                                            Text("Clear Filters")
                                        }
                                    } else {
                                        FilledTonalButton(onClick = onNavigateToPasteLink) {
                                            Text("Paste Recipe Link (Instagram, YouTube, Web)")
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // 2-Column Saved Recipe Cards
                        items(savedRecipes, key = { "saved_${it.id}" }) { recipe ->
                            RecipeGridCard(
                                recipe = recipe,
                                onClick = { onNavigateToViewer(recipe.id) },
                                onToggleFavourite = { viewModel.toggleRecipeFavourite(recipe) },
                                onEdit = { onNavigateToEdit(recipe.id) },
                                onShare = {
                                    if (!recipe.sourceUrl.isNullOrBlank()) {
                                        val sendIntent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, recipe.sourceUrl)
                                            type = "text/plain"
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, "Share recipe link"))
                                    }
                                },
                                onDelete = { recipeToDelete = recipe }
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    recipeToDelete?.let { recipe ->
        AlertDialog(
            onDismissRequest = { recipeToDelete = null },
            title = { Text("Delete Recipe?") },
            text = { Text("Are you sure you want to delete \"${recipe.title ?: "this recipe"}\"? Associated image files will also be removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRecipe(recipe.id)
                        recipeToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { recipeToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showFilterSheet) {
        FilterBottomSheet(
            filter = filter,
            allTags = allTags,
            allDiet = allDiet,
            allEquipment = allEquipment,
            savedCount = savedRecipes.size,
            onDismissRequest = { showFilterSheet = false },
            onDietSelected = { viewModel.setDietTypeFilter(it) },
            onWithoutDietToggled = { viewModel.toggleWithoutDietFilter(it) },
            onComplexitySelected = { viewModel.setComplexityFilter(it) },
            onEquipmentToggled = { viewModel.toggleEquipmentFilter(it) },
            onSpiceSelected = { viewModel.setSpiceLevelFilter(it) },
            onMakeAheadToggled = { viewModel.toggleMakeAheadFilter() },
            onFreezerToggled = { viewModel.toggleFreezerFriendlyFilter() },
            onNeverCookedToggled = { viewModel.toggleNeverCookedFilter() },
            onToReviewToggled = { viewModel.toggleToReviewFilter() },
            onTagToggled = { viewModel.toggleTagFilter(it) },
            onClearAll = { viewModel.clearAllFilters() }
        )
    }
}

/** A single-line pill search field, shorter than a Material text field. */
@Composable
private fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search recipes, ingredients, notes...",
    modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * One row under the search field: the view switch (all recipes or grouped by tag) on the left,
 * the filter, sort and favourites buttons on the right, and between them a chip for each active
 * filter so it can be removed in a tap.
 */
@Composable
private fun LibraryToolbar(
    filter: RecipeFilter,
    viewMode: LibraryViewMode,
    allTags: List<TagEntity>,
    allDiet: List<TagEntity>,
    allEquipment: List<TagEntity>,
    onViewModeSelected: (LibraryViewMode) -> Unit,
    onOpenFilterSheet: () -> Unit,
    onSortSelected: (RecipeSortOrder) -> Unit,
    onFavouriteToggled: () -> Unit,
    onDietSelected: (String?) -> Unit,
    onWithoutDietToggled: (Long) -> Unit,
    onNeverCookedToggled: () -> Unit,
    onComplexitySelected: (String?) -> Unit,
    onEquipmentToggled: (Long) -> Unit,
    onSpiceSelected: (String?) -> Unit,
    onMakeAheadToggled: () -> Unit,
    onFreezerToggled: () -> Unit,
    onToReviewToggled: () -> Unit,
    onTagToggled: (Long) -> Unit,
    onClearAll: () -> Unit
) {
    var showSortMenu by remember { mutableStateOf(false) }
    val selectedTint = MaterialTheme.colorScheme.primary
    val idleTint = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(modifier = Modifier.padding(2.dp)) {
                ViewModeButton(
                    icon = AppIcons.GridView,
                    description = "All recipes",
                    selected = viewMode == LibraryViewMode.ALL,
                    onClick = { onViewModeSelected(LibraryViewMode.ALL) }
                )
                ViewModeButton(
                    icon = AppIcons.Label,
                    description = "Group by tags",
                    selected = viewMode == LibraryViewMode.BY_TAG,
                    onClick = { onViewModeSelected(LibraryViewMode.BY_TAG) }
                )
            }
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            @Composable
            fun ActiveChip(label: String, onRemove: () -> Unit) {
                InputChip(
                    selected = true,
                    onClick = onRemove,
                    label = { Text(label, maxLines = 1) },
                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove $label", modifier = Modifier.size(14.dp)) }
                )
            }

            if (filter.neverCooked) ActiveChip("Never cooked", onNeverCookedToggled)
            if (filter.toReview == true) ActiveChip("To review", onToReviewToggled)
            filter.dietType?.takeIf { it.isNotBlank() }?.let { diet ->
                ActiveChip(diet.replaceFirstChar { it.uppercase() }) { onDietSelected(diet) }
            }
            filter.complexity?.takeIf { it.isNotBlank() }?.let { level ->
                ActiveChip(level.replaceFirstChar { it.uppercase() }) { onComplexitySelected(level) }
            }
            filter.spiceLevel?.takeIf { it.isNotBlank() }?.let { spice ->
                ActiveChip("${spice.replaceFirstChar { it.uppercase() }} spice") { onSpiceSelected(spice) }
            }
            if (filter.makeAhead == true) ActiveChip("Make-ahead", onMakeAheadToggled)
            if (filter.freezerFriendly == true) ActiveChip("Freezer-friendly", onFreezerToggled)
            filter.tagIds.forEach { tagId ->
                allTags.firstOrNull { it.id == tagId }?.let { tag -> ActiveChip(tag.name) { onTagToggled(tagId) } }
            }
            filter.withoutDietTagIds.forEach { dietId ->
                allDiet.firstOrNull { it.id == dietId }?.let { tag -> ActiveChip("Without ${tag.name}") { onWithoutDietToggled(dietId) } }
            }
            filter.equipmentTagIds.forEach { eqId ->
                allEquipment.firstOrNull { it.id == eqId }?.let { tag -> ActiveChip(tag.name) { onEquipmentToggled(eqId) } }
            }
            if (filter.isActive) {
                TextButton(onClick = onClearAll) { Text("Clear all") }
            }
        }

        val activeCount = filter.activeFilterCount
        IconButton(onClick = onOpenFilterSheet) {
            BadgedBox(badge = { if (activeCount > 0) Badge { Text("$activeCount") } }) {
                Icon(
                    AppIcons.Tune,
                    contentDescription = if (activeCount > 0) "Filters ($activeCount active)" else "Filters",
                    tint = if (activeCount > 0) selectedTint else idleTint
                )
            }
        }

        Box {
            IconButton(onClick = { showSortMenu = true }) {
                Icon(
                    AppIcons.Sort,
                    contentDescription = "Sort: ${filter.sortOrder.displayName}",
                    tint = if (filter.sortOrder != RecipeSortOrder.NEWEST_SAVED) selectedTint else idleTint
                )
            }
            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                RecipeSortOrder.entries.forEach { sortOption ->
                    val selected = filter.sortOrder == sortOption
                    DropdownMenuItem(
                        text = {
                            Text(sortOption.displayName, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        },
                        leadingIcon = {
                            if (selected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            onSortSelected(sortOption)
                            showSortMenu = false
                        }
                    )
                }
            }
        }

        val favouritesOnly = filter.isFavourite == true
        IconButton(onClick = onFavouriteToggled) {
            Icon(
                if (favouritesOnly) Icons.Default.Star else AppIcons.StarBorder,
                contentDescription = if (favouritesOnly) "Show all recipes" else "Show favourites only",
                tint = if (favouritesOnly) selectedTint else idleTint
            )
        }
    }
}

@Composable
private fun ViewModeButton(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier
            .size(width = 44.dp, height = 36.dp)
            .semantics { this.selected = selected }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = description,
                modifier = Modifier.size(20.dp),
                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterBottomSheet(
    filter: RecipeFilter,
    allTags: List<TagEntity>,
    allDiet: List<TagEntity>,
    allEquipment: List<TagEntity>,
    savedCount: Int,
    onDismissRequest: () -> Unit,
    onDietSelected: (String?) -> Unit,
    onWithoutDietToggled: (Long) -> Unit,
    onComplexitySelected: (String?) -> Unit,
    onEquipmentToggled: (Long) -> Unit,
    onSpiceSelected: (String?) -> Unit,
    onMakeAheadToggled: () -> Unit,
    onFreezerToggled: () -> Unit,
    onNeverCookedToggled: () -> Unit,
    onToReviewToggled: () -> Unit,
    onTagToggled: (Long) -> Unit,
    onClearAll: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var search by remember { mutableStateOf("") }
    val query = search.trim()
    fun matches(label: String) = query.isEmpty() || label.contains(query, ignoreCase = true)

    /** One filter option: its label, whether it is on, and what tapping it does. */
    class Option(val label: String, val selected: Boolean, val onClick: () -> Unit)

    val sections = listOf(
        "Show only" to listOf(
            Option("Never cooked", filter.neverCooked, onNeverCookedToggled),
            Option("To review", filter.toReview == true, onToReviewToggled)
        ),
        "Diet" to listOf("vegan", "vegetarian", "eggetarian", "non-veg").map { diet ->
            Option(diet.replaceFirstChar { it.uppercase() }, filter.dietType.equals(diet, ignoreCase = true)) { onDietSelected(diet) }
        },
        "Complexity" to listOf("easy", "medium", "hard").map { level ->
            Option(level.replaceFirstChar { it.uppercase() }, filter.complexity.equals(level, ignoreCase = true)) { onComplexitySelected(level) }
        },
        "Spice level" to listOf("mild", "medium", "hot").map { spice ->
            Option("${spice.replaceFirstChar { it.uppercase() }} spice", filter.spiceLevel.equals(spice, ignoreCase = true)) { onSpiceSelected(spice) }
        },
        "Special" to listOf(
            Option("Make-ahead", filter.makeAhead == true, onMakeAheadToggled),
            Option("Freezer-friendly", filter.freezerFriendly == true, onFreezerToggled)
        ),
        "Tags" to allTags.map { tag -> Option(tag.name, tag.id in filter.tagIds) { onTagToggled(tag.id) } },
        "Exclude (allergens & diets)" to allDiet.map { tag ->
            Option("Without ${tag.name}", tag.id in filter.withoutDietTagIds) { onWithoutDietToggled(tag.id) }
        },
        "Equipment" to allEquipment.map { tag -> Option(tag.name, tag.id in filter.equipmentTagIds) { onEquipmentToggled(tag.id) } }
    ).map { (title, options) -> title to options.filter { matches(it.label) } }
        .filter { (_, options) -> options.isNotEmpty() }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Filter Recipes",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (filter.isActive) {
                    TextButton(onClick = onClearAll) {
                        Text("Reset All")
                    }
                }
            }

            LibrarySearchField(
                query = search,
                onQueryChange = { search = it },
                placeholder = "Search filters, tags, equipment...",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                if (sections.isEmpty()) {
                    Text(
                        "No filters match \"$query\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                sections.forEach { (title, options) ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        options.forEach { option ->
                            FilterChip(
                                selected = option.selected,
                                onClick = option.onClick,
                                label = { Text(option.label) }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Button(
                onClick = onDismissRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding()
            ) {
                Text(if (filter.isActive) "Show $savedCount Recipes" else "Done")
            }
        }
    }
}

@Composable
private fun PendingSectionCard(
    pendingRecipes: List<RecipeEntity>,
    isPendingExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onReview: (Long) -> Unit,
    onRetry: (RecipeEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onPastePageText: (RecipeEntity) -> Unit,
    onBrowsePost: ((RecipeEntity) -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Pending Captures",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Text("${pendingRecipes.size}")
                    }
                }

                IconButton(onClick = onToggleExpanded) {
                    Icon(
                        imageVector = if (isPendingExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isPendingExpanded) "Collapse" else "Expand"
                    )
                }
            }

            if (isPendingExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pendingRecipes.forEach { pending ->
                        PendingCard(
                            recipe = pending,
                            onReview = { onReview(pending.id) },
                            onRetry = { onRetry(pending) },
                            onDelete = { onDelete(pending.id) },
                            onPastePageText = { onPastePageText(pending) },
                            onBrowsePost = if (onBrowsePost != null) { { onBrowsePost(pending) } } else null
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecipeCompactCard(
    recipe: RecipeEntity,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var showContextMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .width(160.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showContextMenu = true }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column {
            val imageRelPath = recipe.coverPhotoPath ?: recipe.imagePath
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                if (!imageRelPath.isNullOrBlank()) {
                    val file = File(context.filesDir, imageRelPath)
                    AsyncImage(
                        model = file,
                        contentDescription = recipe.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = recipe.cuisine ?: "Recipe",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onToggleFavourite,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Favourite",
                        tint = if (recipe.isFavourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
            }

            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = recipe.title ?: "Untitled",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                val time = recipe.totalMin ?: ((recipe.prepMin ?: 0) + (recipe.cookMin ?: 0))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (time > 0) "$time min" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!recipe.complexity.isNullOrBlank()) {
                        Text(
                            text = recipe.complexity.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (recipe.isFavourite) "Remove favourite" else "Add to favourites") },
                onClick = {
                    showContextMenu = false
                    onToggleFavourite()
                }
            )
            DropdownMenuItem(
                text = { Text("Edit recipe") },
                onClick = {
                    showContextMenu = false
                    onEdit()
                }
            )
            if (!recipe.sourceUrl.isNullOrBlank()) {
                DropdownMenuItem(
                    text = { Text("Share link") },
                    onClick = {
                        showContextMenu = false
                        onShare()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    showContextMenu = false
                    onDelete()
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecipeGridCard(
    recipe: RecipeEntity,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var showContextMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showContextMenu = true }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column {
            // Recipe Cover (uses cover_photo_path if set, else image_path)
            val imageRelPath = recipe.coverPhotoPath ?: recipe.imagePath
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
            ) {
                if (!imageRelPath.isNullOrBlank()) {
                    val file = File(context.filesDir, imageRelPath)
                    AsyncImage(
                        model = file,
                        contentDescription = recipe.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = recipe.cuisine ?: "Recipe",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Favourite star button overlay
                IconButton(
                    onClick = onToggleFavourite,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Favourite",
                        tint = if (recipe.isFavourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
            }

            // Recipe Details
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = recipe.title ?: "Untitled",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val time = recipe.totalMin ?: ((recipe.prepMin ?: 0) + (recipe.cookMin ?: 0))
                    Text(
                        text = if (time > 0) "$time min" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!recipe.complexity.isNullOrBlank()) {
                        Text(
                            text = recipe.complexity.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Long Press Context Menu
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (recipe.isFavourite) "Remove favourite" else "Add to favourites") },
                onClick = {
                    showContextMenu = false
                    onToggleFavourite()
                }
            )
            DropdownMenuItem(
                text = { Text("Edit recipe") },
                onClick = {
                    showContextMenu = false
                    onEdit()
                }
            )
            if (!recipe.sourceUrl.isNullOrBlank()) {
                DropdownMenuItem(
                    text = { Text("Share link") },
                    onClick = {
                        showContextMenu = false
                        onShare()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    showContextMenu = false
                    onDelete()
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingCard(
    recipe: RecipeEntity,
    onReview: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onPastePageText: (() -> Unit)? = null,
    onBrowsePost: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (recipe.status) {
                RecipeEntity.STATUS_FAILED_FETCH,
                RecipeEntity.STATUS_FAILED_EXTRACT,
                RecipeEntity.STATUS_NO_RECIPE_FOUND -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusText = when (recipe.status) {
                    RecipeEntity.STATUS_PENDING_FETCH -> "Fetching post..."
                    RecipeEntity.STATUS_PENDING_EXTRACT -> "Reading recipe..."
                    RecipeEntity.STATUS_NEEDS_REVIEW -> "Needs review"
                    RecipeEntity.STATUS_FAILED_FETCH -> "Fetch failed"
                    RecipeEntity.STATUS_FAILED_EXTRACT -> "Extraction failed"
                    RecipeEntity.STATUS_NO_RECIPE_FOUND -> "No recipe found"
                    else -> recipe.status
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = when (recipe.status) {
                        RecipeEntity.STATUS_FAILED_FETCH,
                        RecipeEntity.STATUS_FAILED_EXTRACT,
                        RecipeEntity.STATUS_NO_RECIPE_FOUND -> MaterialTheme.colorScheme.error
                        RecipeEntity.STATUS_NEEDS_REVIEW -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.secondary
                    }
                )

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = recipe.title ?: recipe.sourceUrl ?: "Recipe capture in progress",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!recipe.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = recipe.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isFailed = recipe.status == RecipeEntity.STATUS_FAILED_FETCH ||
                    recipe.status == RecipeEntity.STATUS_FAILED_EXTRACT ||
                    recipe.status == RecipeEntity.STATUS_NO_RECIPE_FOUND

                if (isFailed) {
                    OutlinedButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry")
                    }
                }

                if (isFailed && onBrowsePost != null && recipe.sourceType == "instagram") {
                    OutlinedButton(onClick = onBrowsePost) {
                        Text("Browse Post")
                    }
                }

                if (isFailed && onPastePageText != null) {
                    OutlinedButton(onClick = onPastePageText) {
                        val label = when (recipe.sourceType) {
                            "web" -> "Paste page text"
                            "instagram" -> "Paste comment / link"
                            else -> "Paste text"
                        }
                        Text(label)
                    }
                }

                // Nothing to review until the capture job has fetched and read the post.
                val isCapturing = recipe.status == RecipeEntity.STATUS_PENDING_FETCH ||
                    recipe.status == RecipeEntity.STATUS_PENDING_EXTRACT
                Button(onClick = onReview, enabled = !isCapturing) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Review")
                }
            }
        }
    }
}
