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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.repo.RecipeFilter
import org.eu.angsumanray.recipecatcher.data.repo.RecipeSortOrder
import java.io.File

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
                Text(if (recipeForPastingText?.sourceType == "web") "Paste Page Text" else "Paste Text")
            },
            text = {
                Column {
                    Text(
                        text = "Paste recipe text or HTML directly from the page:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pastedContentText,
                        onValueChange = { pastedContentText = it },
                        label = { Text("Recipe text / HTML") },
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
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Recipe Chat")
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
            // Search Bar
            SearchBarSection(
                query = filter.query,
                onQueryChange = { viewModel.updateSearchQuery(it) }
            )

            // Minimal Active & Quick Filter Chips Row
            FilterChipsRow(
                filter = filter,
                allTags = allTags,
                allDiet = allDiet,
                allEquipment = allEquipment,
                onOpenFilterSheet = { showFilterSheet = true },
                onSortSelected = { viewModel.setSortOrder(it) },
                onDietSelected = { viewModel.setDietTypeFilter(it) },
                onWithoutDietToggled = { viewModel.toggleWithoutDietFilter(it) },
                onFavouriteToggled = { viewModel.toggleFavouriteFilter() },
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

            // View Mode Switcher (All Saved vs Group by Tags)
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                SegmentedButton(
                    selected = viewMode == LibraryViewMode.ALL,
                    onClick = { viewModel.setViewMode(LibraryViewMode.ALL) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("All Saved (${savedRecipes.size})")
                }
                SegmentedButton(
                    selected = viewMode == LibraryViewMode.BY_TAG,
                    onClick = { viewModel.setViewMode(LibraryViewMode.BY_TAG) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("Group by Tags (${tagGroups.size})")
                }
            }

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
            onTagToggled = { viewModel.toggleTagFilter(it) },
            onClearAll = { viewModel.clearAllFilters() }
        )
    }
}

@Composable
private fun SearchBarSection(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        placeholder = { Text("Search recipes, ingredients, notes...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun FilterChipsRow(
    filter: RecipeFilter,
    allTags: List<TagEntity>,
    allDiet: List<TagEntity>,
    allEquipment: List<TagEntity>,
    onOpenFilterSheet: () -> Unit,
    onSortSelected: (RecipeSortOrder) -> Unit,
    onDietSelected: (String?) -> Unit,
    onWithoutDietToggled: (Long) -> Unit,
    onFavouriteToggled: () -> Unit,
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Clear all (if any filter active)
        if (filter.isActive) {
            SuggestionChip(
                onClick = onClearAll,
                label = { Text("Clear all") },
                icon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }

        // 1. "Filters" Sheet Button
        val activeCount = filter.activeFilterCount
        FilterChip(
            selected = activeCount > 0,
            onClick = onOpenFilterSheet,
            label = { Text(if (activeCount > 0) "Filters ($activeCount)" else "Filters") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        )

        // 2. Sort order dropdown chip
        Box {
            FilterChip(
                selected = filter.sortOrder != RecipeSortOrder.NEWEST_SAVED,
                onClick = { showSortMenu = true },
                label = { Text(filter.sortOrder.displayName) },
                trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false }
            ) {
                RecipeSortOrder.entries.forEach { sortOption ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                sortOption.displayName,
                                fontWeight = if (filter.sortOrder == sortOption) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            onSortSelected(sortOption)
                            showSortMenu = false
                        }
                    )
                }
            }
        }

        // 3. Quick 1-tap toggles
        FilterChip(
            selected = filter.isFavourite == true,
            onClick = onFavouriteToggled,
            label = { Text("Favourites") }
        )
        FilterChip(
            selected = filter.neverCooked,
            onClick = onNeverCookedToggled,
            label = { Text("Never cooked") }
        )
        FilterChip(
            selected = filter.toReview == true,
            onClick = onToReviewToggled,
            label = { Text("To review") }
        )

        // 4. Specifically active filters with dismiss 'X'
        if (!filter.dietType.isNullOrBlank()) {
            FilterChip(
                selected = true,
                onClick = { onDietSelected(filter.dietType) },
                label = { Text(filter.dietType.replaceFirstChar { it.uppercase() }) },
                trailingIcon = {
                    Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                }
            )
        }

        if (!filter.complexity.isNullOrBlank()) {
            FilterChip(
                selected = true,
                onClick = { onComplexitySelected(filter.complexity) },
                label = { Text(filter.complexity.replaceFirstChar { it.uppercase() }) },
                trailingIcon = {
                    Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                }
            )
        }

        if (!filter.spiceLevel.isNullOrBlank()) {
            FilterChip(
                selected = true,
                onClick = { onSpiceSelected(filter.spiceLevel) },
                label = { Text("${filter.spiceLevel.replaceFirstChar { it.uppercase() }} spice") },
                trailingIcon = {
                    Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                }
            )
        }

        if (filter.makeAhead == true) {
            FilterChip(
                selected = true,
                onClick = onMakeAheadToggled,
                label = { Text("Make-ahead") },
                trailingIcon = {
                    Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                }
            )
        }

        if (filter.freezerFriendly == true) {
            FilterChip(
                selected = true,
                onClick = onFreezerToggled,
                label = { Text("Freezer-friendly") },
                trailingIcon = {
                    Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                }
            )
        }

        filter.tagIds.forEach { tagId ->
            val tag = allTags.firstOrNull { it.id == tagId }
            if (tag != null) {
                FilterChip(
                    selected = true,
                    onClick = { onTagToggled(tagId) },
                    label = { Text(tag.name) },
                    trailingIcon = {
                        Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                    }
                )
            }
        }

        filter.withoutDietTagIds.forEach { dietId ->
            val dietTag = allDiet.firstOrNull { it.id == dietId }
            if (dietTag != null) {
                FilterChip(
                    selected = true,
                    onClick = { onWithoutDietToggled(dietId) },
                    label = { Text("Without ${dietTag.name}") },
                    trailingIcon = {
                        Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                    }
                )
            }
        }

        filter.equipmentTagIds.forEach { eqId ->
            val eqTag = allEquipment.firstOrNull { it.id == eqId }
            if (eqTag != null) {
                FilterChip(
                    selected = true,
                    onClick = { onEquipmentToggled(eqId) },
                    label = { Text(eqTag.name) },
                    trailingIcon = {
                        Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                    }
                )
            }
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
    onTagToggled: (Long) -> Unit,
    onClearAll: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Diet Type
            Text("Diet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("vegan", "vegetarian", "eggetarian", "non-veg").forEach { diet ->
                    val isSelected = filter.dietType.equals(diet, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onDietSelected(diet) },
                        label = { Text(diet.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Complexity
            Text("Complexity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("easy", "medium", "hard").forEach { level ->
                    val isSelected = filter.complexity.equals(level, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onComplexitySelected(level) },
                        label = { Text(level.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Spice Level
            Text("Spice Level", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("mild", "medium", "hot").forEach { spice ->
                    val isSelected = filter.spiceLevel.equals(spice, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSpiceSelected(spice) },
                        label = { Text("${spice.replaceFirstChar { it.uppercase() }} spice") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Special Properties
            Text("Special", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter.makeAhead == true,
                    onClick = onMakeAheadToggled,
                    label = { Text("Make-ahead") }
                )
                FilterChip(
                    selected = filter.freezerFriendly == true,
                    onClick = onFreezerToggled,
                    label = { Text("Freezer-friendly") }
                )
            }

            // 5. Tags
            if (allTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Tags", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allTags.forEach { tag ->
                        val isSelected = tag.id in filter.tagIds
                        FilterChip(
                            selected = isSelected,
                            onClick = { onTagToggled(tag.id) },
                            label = { Text(tag.name) }
                        )
                    }
                }
            }

            // 6. Exclude Allergens / Diets
            if (allDiet.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Exclude (Allergens & Diets)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allDiet.forEach { dietTag ->
                        val isSelected = dietTag.id in filter.withoutDietTagIds
                        FilterChip(
                            selected = isSelected,
                            onClick = { onWithoutDietToggled(dietTag.id) },
                            label = { Text("Without ${dietTag.name}") }
                        )
                    }
                }
            }

            // 7. Equipment
            if (allEquipment.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Equipment", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allEquipment.forEach { eqTag ->
                        val isSelected = eqTag.id in filter.equipmentTagIds
                        FilterChip(
                            selected = isSelected,
                            onClick = { onEquipmentToggled(eqTag.id) },
                            label = { Text(eqTag.name) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onDismissRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
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
    onPastePageText: (RecipeEntity) -> Unit
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
                            onPastePageText = { onPastePageText(pending) }
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

@Composable
private fun PendingCard(
    recipe: RecipeEntity,
    onReview: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onPastePageText: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (recipe.status) {
                RecipeEntity.STATUS_FAILED_FETCH, RecipeEntity.STATUS_FAILED_EXTRACT -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
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
                        RecipeEntity.STATUS_FAILED_FETCH, RecipeEntity.STATUS_FAILED_EXTRACT -> MaterialTheme.colorScheme.error
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

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (recipe.status == RecipeEntity.STATUS_FAILED_FETCH || recipe.status == RecipeEntity.STATUS_FAILED_EXTRACT) {
                    OutlinedButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry")
                    }
                }

                if (recipe.status == RecipeEntity.STATUS_FAILED_FETCH && onPastePageText != null) {
                    OutlinedButton(onClick = onPastePageText) {
                        Text(if (recipe.sourceType == "web") "Paste page text" else "Paste caption")
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
