package org.eu.angsumanray.recipecatcher.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.eu.angsumanray.recipecatcher.core.llm.LibraryEditProposal
import org.eu.angsumanray.recipecatcher.core.llm.RecipeEdit
import org.eu.angsumanray.recipecatcher.core.llm.RecipeSuggestion
import org.eu.angsumanray.recipecatcher.core.llm.WebRecipeSuggestion
import org.eu.angsumanray.recipecatcher.core.model.ChatMessage
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToViewer: (Long) -> Unit,
    onNavigateToReview: (Long) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearErrorMessage()
        }
    }

    // Also re-runs when web cards are added to the last reply.
    val lastMessage = state.messages.lastOrNull()
    LaunchedEffect(state.messages.size, lastMessage?.suggestionsJson) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear Chat History") },
            text = { Text("Are you sure you want to clear all messages in this conversation?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        showClearConfirmDialog = false
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recipe Chat", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.messages.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirmDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear History")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (state.isSending) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Message list
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (state.messages.isEmpty() && !state.isLoading) {
                    EmptyChatGreeting(onPromptSelected = { viewModel.sendMessage(it) })
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.messages, key = { it.id }) { message ->
                            ChatMessageItem(
                                message = message,
                                viewModel = viewModel,
                                recipeTitles = state.recipeTitles,
                                savingUrls = state.savingUrls,
                                editBusy = message.id in state.editBusyMessageIds,
                                onNavigateToViewer = onNavigateToViewer,
                                onNavigateToReview = onNavigateToReview
                            )
                        }
                    }
                }
            }

            // Quick prompts horizontal bar
            val quickPrompts = listOf(
                "What can I cook tonight?",
                "Quick 20-min dinner",
                "Something with paneer",
                "Use up my ingredients",
                "Healthy high-protein meal",
                "Tag my air fryer recipes"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (prompt in quickPrompts) {
                    AssistChip(
                        onClick = { viewModel.sendMessage(prompt) },
                        enabled = !state.isSending && !state.isLoading,
                        label = { Text(prompt, style = MaterialTheme.typography.bodySmall) }
                    )
                }
            }

            // Input section at bottom
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        FilterChip(
                            selected = state.includeWeb,
                            onClick = { viewModel.toggleIncludeWeb() },
                            label = { Text("Include web", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )

                        // Model Selector Dropdown
                        var modelMenuExpanded by remember { mutableStateOf(false) }
                        Box {
                            AssistChip(
                                onClick = { modelMenuExpanded = true },
                                label = {
                                    Text(
                                        text = if (state.chatModelId == SettingsStore.CHAT_MODEL_SONNET) "Claude Sonnet 5.5" else "Claude Haiku 5.5",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                            DropdownMenu(
                                expanded = modelMenuExpanded,
                                onDismissRequest = { modelMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Claude Haiku 5.5") },
                                    onClick = {
                                        viewModel.setChatModelId(SettingsStore.CHAT_MODEL_HAIKU)
                                        modelMenuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Claude Sonnet 5.5") },
                                    onClick = {
                                        viewModel.setChatModelId(SettingsStore.CHAT_MODEL_SONNET)
                                        modelMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = state.inputMessage,
                            onValueChange = { viewModel.onInputMessageChange(it) },
                            placeholder = { Text("Ask about recipes or ingredients...") },
                            modifier = Modifier.weight(1f),
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = { viewModel.sendMessage() },
                            enabled = state.inputMessage.isNotBlank() && !state.isSending,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(
                                    if (state.inputMessage.isNotBlank() && !state.isSending)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (state.inputMessage.isNotBlank() && !state.isSending)
                                    MaterialTheme.colorScheme.onPrimary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChatGreeting(onPromptSelected: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Star,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "What would you like to cook?",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Ask for recipe ideas from your library, suggest variations, search the web for new dishes, or ask to update tags, allergens and diet across your recipes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    viewModel: ChatViewModel,
    recipeTitles: Map<Long, String>,
    savingUrls: Set<String>,
    editBusy: Boolean,
    onNavigateToViewer: (Long) -> Unit,
    onNavigateToReview: (Long) -> Unit
) {
    val isUser = message.role.equals("user", ignoreCase = true)
    val timeFormatted = remember(message.createdAt) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.createdAt))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth(if (isUser) 0.85f else 0.95f)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                // If assistant message has structured suggestions
                if (!isUser) {
                    val planResult = remember(message.suggestionsJson) {
                        viewModel.parseSuggestions(message.suggestionsJson)
                    }

                    if (planResult != null) {
                        // Follow-up question
                        if (planResult.followUpQuestion.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = planResult.followUpQuestion,
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Library Recipe Cards
                        if (planResult.suggestions.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "From Your Library:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            for (suggestion in planResult.suggestions) {
                                val title = recipeTitles[suggestion.recipeId]
                                LibraryRecipeCard(
                                    suggestion = suggestion,
                                    recipeTitle = title ?: "No longer in your library",
                                    onTap = if (title != null) {
                                        { onNavigateToViewer(suggestion.recipeId) }
                                    } else null
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }

                        // Web Recipe Cards
                        if (planResult.webSuggestions.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "From the Web:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            for (webPick in planResult.webSuggestions) {
                                WebRecipeCard(
                                    suggestion = webPick,
                                    isSaving = webPick.url in savingUrls,
                                    onSave = {
                                        viewModel.saveWebRecipe(webPick.url) { savedId ->
                                            onNavigateToReview(savedId)
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }

                        planResult.edits?.let { proposal ->
                            Spacer(modifier = Modifier.height(10.dp))
                            LibraryEditCard(
                                proposal = proposal,
                                recipeTitles = recipeTitles,
                                busy = editBusy,
                                onApply = { skipped -> viewModel.applyEdits(message.id, skipped) },
                                onUndo = { viewModel.undoEdits(message.id) },
                                onDismiss = { viewModel.dismissEdits(message.id) },
                                onOpenRecipe = onNavigateToViewer
                            )
                        }

                        if (planResult.webNote.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = planResult.webNote,
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (planResult.webUsage != null && planResult.webSuggestions.isEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No full web recipes matched this time.",
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isUser && message.includeWeb) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Web included",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = timeFormatted,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUser)
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/** Library changes proposed by chat, for the user to check, apply, dismiss or undo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryEditCard(
    proposal: LibraryEditProposal,
    recipeTitles: Map<Long, String>,
    busy: Boolean,
    onApply: (skipped: Set<Long>) -> Unit,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    onOpenRecipe: (Long) -> Unit
) {
    val isProposed = proposal.status == LibraryEditProposal.STATUS_PROPOSED
    var skipped by remember(proposal.edits) { mutableStateOf(emptySet<Long>()) }
    var showAll by remember { mutableStateOf(false) }
    val visibleEdits = if (showAll) proposal.edits else proposal.edits.take(COLLAPSED_EDIT_ROWS)
    val selectedCount = proposal.edits.count { it.recipeId !in skipped }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Proposed changes · ${proposal.edits.size} recipe${if (proposal.edits.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            if (proposal.summary.isNotBlank()) {
                Text(
                    text = proposal.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            visibleEdits.forEach { edit ->
                val title = recipeTitles[edit.recipeId]
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                    if (isProposed) {
                        Checkbox(
                            checked = edit.recipeId !in skipped,
                            onCheckedChange = { checked ->
                                skipped = if (checked) skipped - edit.recipeId else skipped + edit.recipeId
                            },
                            enabled = !busy
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = if (isProposed) 10.dp else 4.dp)
                    ) {
                        Text(
                            text = title ?: "Recipe no longer in your library",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (title != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = if (title != null) Modifier.clickable { onOpenRecipe(edit.recipeId) } else Modifier
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            editLabels(edit).forEach { label ->
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (edit.reason.isNotBlank()) {
                            Text(
                                text = edit.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (proposal.edits.size > COLLAPSED_EDIT_ROWS) {
                TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Show fewer" else "Show all ${proposal.edits.size}")
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            when (proposal.status) {
                LibraryEditProposal.STATUS_PROPOSED -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { onApply(skipped) }, enabled = !busy && selectedCount > 0) {
                        Text(if (busy) "Applying..." else "Apply to $selectedCount")
                    }
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Dismiss") }
                }
                LibraryEditProposal.STATUS_APPLIED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Applied to ${proposal.applied.size} recipe${if (proposal.applied.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onUndo, enabled = !busy) { Text(if (busy) "Undoing..." else "Undo") }
                }
                else -> Text(
                    text = if (proposal.status == LibraryEditProposal.STATUS_UNDONE) "Undone" else "Dismissed",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private const val COLLAPSED_EDIT_ROWS = 8

/** Short labels for each change in [edit], e.g. "+ dairy (contains)" or "Diet → vegetarian". */
private fun editLabels(edit: RecipeEdit): List<String> {
    fun kindSuffix(kind: String) = when (kind) {
        LibraryEditProposal.KIND_CONTAINS -> " (contains)"
        LibraryEditProposal.KIND_EQUIPMENT -> " (equipment)"
        LibraryEditProposal.KIND_CONSUMABLE -> " (consumable)"
        else -> ""
    }
    return buildList {
        edit.add.forEach { add("+ ${it.name}${kindSuffix(it.kind)}") }
        edit.remove.forEach { add("− ${it.name}${kindSuffix(it.kind)}") }
        edit.dietType?.let { add("Diet → $it") }
        edit.complexity?.let { add("Complexity → $it") }
        edit.spiceLevel?.let { add("Spice → $it") }
        edit.makeAhead?.let { add(if (it) "Make-ahead" else "Not make-ahead") }
        edit.freezerFriendly?.let { add(if (it) "Freezer-friendly" else "Not freezer-friendly") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryRecipeCard(
    suggestion: RecipeSuggestion,
    recipeTitle: String,
    onTap: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = recipeTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    fontStyle = if (onTap == null) FontStyle.Italic else FontStyle.Normal,
                    color = if (onTap != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            if (suggestion.reason.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = suggestion.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (suggestion.missing.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Missing: " + suggestion.missing.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun WebRecipeCard(
    suggestion: WebRecipeSuggestion,
    isSaving: Boolean,
    onSave: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = suggestion.site.ifBlank { "Web Recipe" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = suggestion.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            if (suggestion.reason.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = suggestion.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(suggestion.url)))
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open", style = MaterialTheme.typography.labelSmall)
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onSave,
                    enabled = !isSaving,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(if (isSaving) "Saving…" else "Save", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
