package org.eu.angsumanray.recipecatcher.ui.importer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import org.eu.angsumanray.recipecatcher.data.db.entity.ImportItemEntity
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val preview by viewModel.preview.collectAsState()
    val isParsing by viewModel.isParsing.collectAsState()
    val parseError by viewModel.parseError.collectAsState()
    val tagWithCollections by viewModel.tagWithCollections.collectAsState()
    val progressState by viewModel.progressState.collectAsState()

    var pastedText by remember { mutableStateOf("") }
    var showPasteSection by remember { mutableStateOf(false) }

    // SAF file picker for Instagram export ZIP or JSON
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val isZip = uri.lastPathSegment?.endsWith(".zip", ignoreCase = true) == true ||
                context.contentResolver.getType(uri)?.contains("zip", ignoreCase = true) == true
            context.contentResolver.openInputStream(uri)?.let { stream ->
                viewModel.parseStream(stream, isZip)
            }
        }
    }

    // Parsing Dialog
    if (isParsing) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Analyzing Export") },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text("Searching for Instagram links and collections...")
                }
            }
        )
    }

    // Parse Error Dialog
    parseError?.let { err ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPreview() },
            title = { Text("Import Notice") },
            text = { Text(err) },
            confirmButton = {
                Button(onClick = { viewModel.dismissPreview() }) {
                    Text("OK")
                }
            }
        )
    }

    // Preview Confirmation Dialog
    preview?.let { prev ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPreview() },
            title = { Text("Import Preview") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Found ${prev.totalFound} Instagram post links.\n" +
                            "${prev.newCount} new posts to import\n" +
                            "${prev.alreadySavedCount} already saved in library or queue.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (prev.hasCollections) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Tag with collection names", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = tagWithCollections,
                                onCheckedChange = { viewModel.setTagWithCollections(it) }
                            )
                        }
                    }

                    if (prev.newCount > 0) {
                        Text(
                            text = "Posts will be imported in the background at ~30s intervals with 'To review' tags.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmImport() },
                    enabled = prev.newCount > 0
                ) {
                    Text("Start Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPreview() }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Saves") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Queue & Progress Card (when queue is active or has items)
            val hasQueueItems = progressState.queuedCount > 0 ||
                progressState.doneCount > 0 ||
                progressState.skippedCount > 0 ||
                progressState.failedCount > 0

            if (hasQueueItems) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Import Progress",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )

                                when {
                                    progressState.isUnderCooldown -> {
                                        Text(
                                            text = "Cooldown (${progressState.cooldownRemainingSeconds / 60}m)",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                    progressState.isPaused -> {
                                        Text(
                                            text = "Paused",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                    progressState.queuedCount > 0 -> {
                                        Text(
                                            text = "Active (~${progressState.estimatedFinishMinutes}m left)",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                    else -> {
                                        Text(
                                            text = "Finished",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }

                            val total = progressState.queuedCount + progressState.doneCount + progressState.skippedCount + progressState.failedCount
                            val progress = if (total > 0) (progressState.doneCount + progressState.skippedCount).toFloat() / total.toFloat() else 0f
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                MetricItem(label = "Done", count = progressState.doneCount, color = MaterialTheme.colorScheme.primary)
                                MetricItem(label = "Remaining", count = progressState.queuedCount)
                                MetricItem(label = "Skipped", count = progressState.skippedCount, color = MaterialTheme.colorScheme.secondary)
                                MetricItem(label = "Failed", count = progressState.failedCount, color = MaterialTheme.colorScheme.error)
                            }

                            // Controls Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (progressState.isPaused) {
                                    Button(
                                        onClick = { viewModel.resumeImport() },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Resume")
                                    }
                                } else if (progressState.queuedCount > 0) {
                                    OutlinedButton(
                                        onClick = { viewModel.pauseImport() },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Pause")
                                    }
                                }

                                if (progressState.failedCount > 0 || progressState.skippedCount > 0) {
                                    OutlinedButton(
                                        onClick = { viewModel.retryAllSkippedAndFailed() },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Retry All")
                                    }
                                }
                            }

                            if (progressState.queuedCount == 0 && (progressState.doneCount > 0 || progressState.skippedCount > 0)) {
                                TextButton(
                                    onClick = { viewModel.clearCompleted() },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Clear Completed")
                                }
                            }
                        }
                    }
                }
            }

            // Input Section: Select export file or Paste links
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Import Instagram Saves",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Download your information from Instagram in JSON format to get saved_posts.json or a ZIP export.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = {
                                filePicker.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Select Export File (ZIP / JSON)")
                        }

                        OutlinedButton(
                            onClick = { showPasteSection = !showPasteSection },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (showPasteSection) "Hide Pasted Links" else "Paste Instagram Links Manually")
                        }

                        if (showPasteSection) {
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = pastedText,
                                onValueChange = { pastedText = it },
                                label = { Text("Paste Instagram URLs") },
                                placeholder = { Text("https://www.instagram.com/p/...\nhttps://www.instagram.com/reel/...") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                maxLines = 8
                            )
                            Button(
                                onClick = {
                                    viewModel.parseText(pastedText)
                                    pastedText = ""
                                },
                                enabled = pastedText.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Preview Pasted Links")
                            }
                        }
                    }
                }
            }

            // Skipped & Failed Items List
            if (progressState.skippedAndFailedItems.isNotEmpty()) {
                item {
                    Text(
                        text = "Skipped & Failed Items (${progressState.skippedAndFailedItems.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(progressState.skippedAndFailedItems, key = { "skipped_${it.id}" }) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (item.status == ImportItemEntity.STATUS_FAILED) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Post: ${item.sourceKey}",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = item.lastError ?: (if (item.status == ImportItemEntity.STATUS_SKIPPED) "No recipe found" else "Failed"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (item.status == ImportItemEntity.STATUS_FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!item.collection.isNullOrBlank()) {
                                    Text(
                                        text = "Collection: ${item.collection}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            IconButton(onClick = { viewModel.retryItem(item.id) }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Retry this item")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, count: Int, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "$count", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = color)
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
