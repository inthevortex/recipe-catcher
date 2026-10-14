package org.eu.angsumanray.recipecatcher.ui.library

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
    onNavigateToReview: (Long) -> Unit
) {
    val filter by viewModel.filter.collectAsState()
    val savedRecipes by viewModel.savedRecipes.collectAsState()
    val pendingRecipes by viewModel.pendingRecipes.collectAsState()
    val isPendingExpanded by viewModel.isPendingExpanded.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val allDiet by viewModel.allDiet.collectAsState()
    val allEquipment by viewModel.allEquipment.collectAsState()

    val context = LocalContext.current
    var showFabMenu by remember { mutableStateOf(false) }
    var recipeToDelete by remember { mutableStateOf<RecipeEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recipe Catcher", fontWeight = FontWeight.Bold) },
                actions = {
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

            // Pinned Active & Available Filter Chips Row
            FilterChipsRow(
                filter = filter,
                allTags = allTags,
                allDiet = allDiet,
                allEquipment = allEquipment,
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

                                    IconButton(onClick = { viewModel.togglePendingExpanded() }) {
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
                                                onReview = { onNavigateToReview(pending.id) },
                                                onRetry = { viewModel.retryCapture(pending) },
                                                onDelete = { viewModel.deleteRecipe(pending.id) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
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
                                        Text("Paste Instagram or Web Link")
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

        // Sort order chip
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

        val chips = buildList {
            add(FilterChipSpec("Favourites", filter.isFavourite == true, onFavouriteToggled))
            add(FilterChipSpec("Never cooked", filter.neverCooked, onNeverCookedToggled))
            add(FilterChipSpec("To review", filter.toReview == true, onToReviewToggled))
            listOf("vegan", "vegetarian", "eggetarian", "non-veg").forEach { diet ->
                add(FilterChipSpec(diet.capitalize(), filter.dietType.equals(diet, ignoreCase = true)) { onDietSelected(diet) })
            }
            allDiet.forEach { dietTag ->
                add(FilterChipSpec("Without ${dietTag.name}", dietTag.id in filter.withoutDietTagIds) { onWithoutDietToggled(dietTag.id) })
            }
            listOf("easy", "medium", "hard").forEach { level ->
                add(FilterChipSpec(level.capitalize(), filter.complexity.equals(level, ignoreCase = true)) { onComplexitySelected(level) })
            }
            allEquipment.forEach { eqTag ->
                add(FilterChipSpec(eqTag.name, eqTag.id in filter.equipmentTagIds) { onEquipmentToggled(eqTag.id) })
            }
            listOf("mild", "medium", "hot").forEach { spice ->
                add(FilterChipSpec("${spice.capitalize()} spice", filter.spiceLevel.equals(spice, ignoreCase = true)) { onSpiceSelected(spice) })
            }
            add(FilterChipSpec("Make-ahead", filter.makeAhead == true, onMakeAheadToggled))
            add(FilterChipSpec("Freezer-friendly", filter.freezerFriendly == true, onFreezerToggled))
            allTags.forEach { tag ->
                add(FilterChipSpec(tag.name, tag.id in filter.tagIds) { onTagToggled(tag.id) })
            }
        }

        // Selected chips stay pinned at the start of the row.
        chips.sortedByDescending { it.selected }.forEach { chip ->
            FilterChip(
                selected = chip.selected,
                onClick = chip.onClick,
                label = { Text(chip.label) }
            )
        }
    }
}

private data class FilterChipSpec(val label: String, val selected: Boolean, val onClick: () -> Unit)

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
                            text = recipe.complexity.capitalize(),
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
    onDelete: () -> Unit
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
