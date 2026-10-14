package org.eu.angsumanray.recipecatcher.ui.today

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanEntryEntity
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    onNavigateToPlan: () -> Unit,
    onNavigateToViewer: (Long) -> Unit,
    onNavigateToCookingMode: (Long, Int) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Today", fontWeight = FontWeight.Bold)
                        val dateFormatted = state.date.format(
                            DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())
                        )
                        Text(
                            text = dateFormatted,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
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

        val allItems = state.todayEntries
        val cookItems = allItems.filter { it.entry.kind == PlanEntryEntity.KIND_COOK }
        val prepItems = allItems.filter { it.entry.kind == PlanEntryEntity.KIND_PREP }
        val leftoverItems = allItems.filter { it.entry.kind == PlanEntryEntity.KIND_LEFTOVER }
        val tonightItems = state.tonightPrepEntries

        val hasAny = allItems.isNotEmpty() || tonightItems.isNotEmpty()

        if (!hasAny) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Nothing planned for today",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Plan your meals, schedule leftovers, and build your grocery checklist.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onNavigateToPlan) {
                            Text("Plan the Week")
                        }
                    }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header stats
            item {
                val total = allItems.size
                val done = allItems.count { it.isDone }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Today's Schedule",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "$done of $total meals completed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        OutlinedButton(onClick = onNavigateToPlan) {
                            Text("Week View")
                        }
                    }
                }
            }

            // To Cook
            if (cookItems.isNotEmpty()) {
                item {
                    Text(
                        text = "To Cook",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(cookItems, key = { "cook_${it.entry.id}" }) { item ->
                    TodayMealCard(
                        item = item,
                        onToggleDone = { viewModel.toggleEntryDone(item.entry.id) },
                        onOpenRecipe = { item.recipe?.id?.let { onNavigateToViewer(it) } },
                        onStartCooking = { item.recipe?.id?.let { onNavigateToCookingMode(it, item.entry.portions) } }
                    )
                }
            }

            // To Prep
            if (prepItems.isNotEmpty()) {
                item {
                    Text(
                        text = "To Prep",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(prepItems, key = { "prep_${it.entry.id}" }) { item ->
                    TodayMealCard(
                        item = item,
                        onToggleDone = { viewModel.toggleEntryDone(item.entry.id) },
                        onOpenRecipe = { item.recipe?.id?.let { onNavigateToViewer(it) } },
                        onStartCooking = null
                    )
                }
            }

            // Leftovers
            if (leftoverItems.isNotEmpty()) {
                item {
                    Text(
                        text = "Leftovers to Eat",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(leftoverItems, key = { "leftover_${it.entry.id}" }) { item ->
                    TodayMealCard(
                        item = item,
                        onToggleDone = { viewModel.toggleEntryDone(item.entry.id) },
                        onOpenRecipe = { item.recipe?.id?.let { onNavigateToViewer(it) } },
                        onStartCooking = null
                    )
                }
            }

            // Tonight's Prep for Tomorrow
            if (tonightItems.isNotEmpty()) {
                item {
                    Text(
                        text = "Tonight's Prep for Tomorrow",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                items(tonightItems, key = { "tonight_${it.entry.id}" }) { item ->
                    TodayMealCard(
                        item = item,
                        onToggleDone = { viewModel.toggleEntryDone(item.entry.id) },
                        onOpenRecipe = { item.recipe?.id?.let { onNavigateToViewer(it) } },
                        onStartCooking = null
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun TodayMealCard(
    item: TodayEntryItem,
    onToggleDone: () -> Unit,
    onOpenRecipe: () -> Unit,
    onStartCooking: (() -> Unit)?
) {
    val recipe = item.recipe
    val title = recipe?.title ?: item.entry.text ?: "Meal"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenRecipe() },
        colors = CardDefaults.cardColors(
            containerColor = if (item.isDone) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Slot badge
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    val slotText = buildString {
                        append(item.slot.name)
                        item.slot.time?.let { append(" • $it") }
                    }
                    Text(
                        text = slotText,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Kind badge
                Surface(
                    color = when (item.entry.kind) {
                        PlanEntryEntity.KIND_COOK -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        PlanEntryEntity.KIND_LEFTOVER -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                        else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = item.entry.kind.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Checkbox / Done toggle button
                IconButton(onClick = onToggleDone, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (item.isDone) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                        contentDescription = "Toggle done",
                        tint = if (item.isDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Title
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
                color = if (item.isDone) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
            )

            // Portions info
            val portionsText = if (item.entry.kind == PlanEntryEntity.KIND_COOK) {
                "${item.entry.portions} portions (eat ${item.entry.portionsEatenNow} now)"
            } else {
                "${item.entry.portions} portion(s)"
            }
            Text(
                text = portionsText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Start Cooking Action
            if (onStartCooking != null && !item.isDone) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(
                        onClick = onStartCooking,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Start Cooking")
                    }
                }
            }
        }
    }
}
