package org.eu.angsumanray.recipecatcher.ui.library

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onNavigateToPasteLink: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToReview: (Long) -> Unit
) {
    val savedRecipes by viewModel.savedRecipes.collectAsState()
    val pendingRecipes by viewModel.pendingRecipes.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recipe Catcher", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onNavigateToPasteLink) {
                        Icon(Icons.Default.Add, contentDescription = "Paste Link")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Pending Section
            if (pendingRecipes.isNotEmpty()) {
                item {
                    Text(
                        text = "Pending Captures (${pendingRecipes.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(pendingRecipes, key = { "pending_${it.id}" }) { recipe ->
                    PendingRecipeCard(
                        recipe = recipe,
                        onReview = { onNavigateToReview(recipe.id) },
                        onRetry = { viewModel.retryCapture(recipe) },
                        onDelete = { viewModel.deleteRecipe(recipe.id) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Saved Section
            item {
                Text(
                    text = "Saved Recipes (${savedRecipes.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (savedRecipes.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "No recipes saved yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(onClick = onNavigateToPasteLink) {
                                Text("Paste Instagram or Web Link")
                            }
                        }
                    }
                }
            } else {
                items(savedRecipes, key = { "saved_${it.id}" }) { recipe ->
                    SavedRecipeCard(
                        recipe = recipe,
                        onClick = { onNavigateToReview(recipe.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingRecipeCard(
    recipe: RecipeEntity,
    onReview: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusText = when (recipe.status) {
                    RecipeEntity.STATUS_PENDING_FETCH -> "Fetching post..."
                    RecipeEntity.STATUS_PENDING_EXTRACT -> "Reading recipe with AI..."
                    RecipeEntity.STATUS_NEEDS_REVIEW -> "Ready for Review"
                    RecipeEntity.STATUS_FAILED_FETCH -> "Fetch Failed"
                    RecipeEntity.STATUS_FAILED_EXTRACT -> "Extraction Failed"
                    RecipeEntity.STATUS_NO_RECIPE_FOUND -> "No Recipe Found"
                    else -> recipe.status
                }
                SuggestionChip(
                    onClick = {},
                    label = { Text(statusText) }
                )

                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val displayTitle = recipe.title ?: recipe.sourceUrl ?: "Recipe #${recipe.id}"
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (!recipe.authorHandle.isNullOrBlank()) {
                Text(
                    text = "by @${recipe.authorHandle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!recipe.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = recipe.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recipe.status == RecipeEntity.STATUS_NEEDS_REVIEW) {
                    Button(onClick = onReview) {
                        Text("Review & Save")
                    }
                } else if (recipe.status.startsWith("failed_") || recipe.status == RecipeEntity.STATUS_NO_RECIPE_FOUND) {
                    OutlinedButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedRecipeCard(
    recipe: RecipeEntity,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val imageFile = recipe.imagePath?.let { File(context.filesDir, it) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (imageFile != null && imageFile.exists()) {
                AsyncImage(
                    model = imageFile,
                    contentDescription = recipe.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(MaterialTheme.shapes.medium)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recipe.title ?: "Untitled Recipe",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (!recipe.authorHandle.isNullOrBlank()) {
                    Text(
                        text = "by @${recipe.authorHandle}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recipe.totalMin?.let {
                        Text("$it min", style = MaterialTheme.typography.bodySmall)
                    }
                    recipe.complexity?.let {
                        Text(it.replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.bodySmall)
                    }
                    recipe.dietType?.let {
                        Text(it.replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
