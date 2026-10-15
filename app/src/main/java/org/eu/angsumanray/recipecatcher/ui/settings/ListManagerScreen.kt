package org.eu.angsumanray.recipecatcher.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListManagerScreen(
    viewModel: ListManagerViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    val kinds = listOf(
        Pair(TagEntity.KIND_TAG, "Tags"),
        Pair(TagEntity.KIND_DIET, "Diet"),
        Pair(TagEntity.KIND_EQUIPMENT, "Equipment"),
        Pair(TagEntity.KIND_CONSUMABLE, "Consumables")
    )

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    var showAddDialog by remember { mutableStateOf(false) }
    var addNameInput by remember { mutableStateOf("") }

    var tagToRename by remember { mutableStateOf<TagEntity?>(null) }
    var renameInput by remember { mutableStateOf("") }

    var tagToDelete by remember { mutableStateOf<TagEntity?>(null) }

    var tagToMerge by remember { mutableStateOf<TagEntity?>(null) }
    var targetMergeTagId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("List Manager") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                addNameInput = ""
                showAddDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add item")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                edgePadding = 16.dp,
                divider = {}
            ) {
                kinds.forEachIndexed { index, pair ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = {
                            selectedTabIndex = index
                            viewModel.selectKind(pair.first)
                        },
                        text = { Text(pair.second, maxLines = 1) }
                    )
                }
            }

            val currentItems = state.currentItems

            if (currentItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No items yet. Tap + to add one.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(currentItems, key = { "tag_${it.id}" }) { tag ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tag.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(onClick = {
                                        tagToRename = tag
                                        renameInput = tag.name
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(20.dp))
                                    }

                                    IconButton(onClick = {
                                        tagToMerge = tag
                                        targetMergeTagId = null
                                    }) {
                                        Icon(Icons.Default.Share, contentDescription = "Merge into", modifier = Modifier.size(20.dp))
                                    }

                                    IconButton(onClick = { tagToDelete = tag }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(72.dp))
                    }
                }
            }
        }
    }

    // Dialog: Add Tag
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add ${kinds[selectedTabIndex].second}") },
            text = {
                OutlinedTextField(
                    value = addNameInput,
                    onValueChange = { addNameInput = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (addNameInput.isNotBlank()) {
                            viewModel.addTag(addNameInput)
                            showAddDialog = false
                        }
                    },
                    enabled = addNameInput.isNotBlank()
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Rename Tag
    tagToRename?.let { tag ->
        AlertDialog(
            onDismissRequest = { tagToRename = null },
            title = { Text("Rename \"${tag.name}\"") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("New Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameInput.isNotBlank()) {
                            viewModel.renameTag(tag.id, renameInput)
                            tagToRename = null
                        }
                    },
                    enabled = renameInput.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { tagToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Delete Tag
    tagToDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = { tagToDelete = null },
            title = { Text("Delete \"${tag.name}\"?") },
            text = { Text("Deleting this item will remove it from all linked recipes. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTag(tag.id)
                        tagToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { tagToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Merge Tag
    tagToMerge?.let { sourceTag ->
        val candidateTargets = state.currentItems.filter { it.id != sourceTag.id }
        var dropdownExpanded by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { tagToMerge = null },
            title = { Text("Merge \"${sourceTag.name}\"") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select an item to merge \"${sourceTag.name}\" into. All recipes linking to \"${sourceTag.name}\" will link to the target item instead, and \"${sourceTag.name}\" will be deleted.")

                    Box {
                        val selectedTargetName = candidateTargets.find { it.id == targetMergeTagId }?.name ?: "Select target item..."
                        OutlinedButton(
                            onClick = { dropdownExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(selectedTargetName)
                        }
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }
                        ) {
                            candidateTargets.forEach { target ->
                                DropdownMenuItem(
                                    text = { Text(target.name) },
                                    onClick = {
                                        targetMergeTagId = target.id
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        targetMergeTagId?.let { targetId ->
                            viewModel.mergeTags(sourceTag.id, targetId)
                            tagToMerge = null
                        }
                    },
                    enabled = targetMergeTagId != null
                ) {
                    Text("Merge")
                }
            },
            dismissButton = {
                TextButton(onClick = { tagToMerge = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
