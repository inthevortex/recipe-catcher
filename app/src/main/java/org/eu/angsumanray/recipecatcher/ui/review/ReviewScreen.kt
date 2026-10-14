package org.eu.angsumanray.recipecatcher.ui.review

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    onNavigateBack: () -> Unit,
    onSaveSuccess: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var showRawCaption by remember { mutableStateOf(false) }

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review Recipe") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.saveRecipe(onSaveSuccess) }) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                }
            )
        },
        bottomBar = {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Button(
                    onClick = { viewModel.saveRecipe(onSaveSuccess) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save to Library")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = { viewModel.updateTitle(it) },
                label = { Text("Recipe Title") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.description,
                onValueChange = { viewModel.updateDescription(it) },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            // Times and Servings
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.servings.toString(),
                    onValueChange = { it.toIntOrNull()?.let { s -> viewModel.updateServings(s) } },
                    label = { Text("Servings") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.prepMin.toString(),
                    onValueChange = { it.toIntOrNull()?.let { p -> viewModel.updateTimes(p, state.cookMin, state.totalMin) } },
                    label = { Text("Prep (min)") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.cookMin.toString(),
                    onValueChange = { it.toIntOrNull()?.let { c -> viewModel.updateTimes(state.prepMin, c, state.totalMin) } },
                    label = { Text("Cook (min)") },
                    modifier = Modifier.weight(1f)
                )
            }

            // Complexity & Spice
            Text("Complexity", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("easy", "medium", "hard").forEach { level ->
                    FilterChip(
                        selected = state.complexity.equals(level, ignoreCase = true),
                        onClick = { viewModel.updateComplexity(level) },
                        label = { Text(level.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            Text("Diet Type", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("vegan", "vegetarian", "eggetarian", "non-veg").forEach { diet ->
                    FilterChip(
                        selected = state.dietType.equals(diet, ignoreCase = true),
                        onClick = { viewModel.updateDietType(diet) },
                        label = { Text(diet.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            // Toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Make Ahead Friendly")
                Switch(
                    checked = state.makeAhead,
                    onCheckedChange = { viewModel.toggleMakeAhead(it) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Freezer Friendly")
                Switch(
                    checked = state.freezerFriendly,
                    onCheckedChange = { viewModel.toggleFreezerFriendly(it) }
                )
            }

            // Tags
            Text("Tags", fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.allTags.forEach { tag ->
                    val isSelected = state.selectedTagIds.contains(tag.id)
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.toggleTag(tag.id) },
                        label = { Text(tag.name) }
                    )
                }
            }

            // Ingredients
            Text("Ingredients (${state.ingredients.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            state.ingredients.forEachIndexed { index, ing ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = ing.quantity?.toString() ?: "",
                                onValueChange = {
                                    val q = it.toDoubleOrNull()
                                    viewModel.updateIngredient(index, ing.name, q, ing.unit)
                                },
                                label = { Text("Qty") },
                                modifier = Modifier.width(80.dp)
                            )
                            OutlinedTextField(
                                value = ing.unit.orEmpty(),
                                onValueChange = { viewModel.updateIngredient(index, ing.name, ing.quantity, it) },
                                label = { Text("Unit") },
                                modifier = Modifier.width(90.dp)
                            )
                            OutlinedTextField(
                                value = ing.name,
                                onValueChange = { viewModel.updateIngredient(index, it, ing.quantity, ing.unit) },
                                label = { Text("Ingredient") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Steps
            Text("Steps (${state.steps.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            state.steps.forEachIndexed { index, step ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Step ${index + 1}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = step.text,
                            onValueChange = { viewModel.updateStep(index, it) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                    }
                }
            }

            // Raw Source Text Collapsible
            TextButton(onClick = { showRawCaption = !showRawCaption }) {
                Text(if (showRawCaption) "Hide Raw Source Text" else "Show Raw Source Text")
            }
            if (showRawCaption && state.captionRaw.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = state.captionRaw,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(60.dp))
        }
    }
}
