package org.eu.angsumanray.recipecatcher.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.eu.angsumanray.recipecatcher.backup.BackupManager
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToListManager: () -> Unit = {},
    onNavigateToImportSaves: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val apiKey by viewModel.apiKey.collectAsState()
    val modelId by viewModel.modelId.collectAsState()
    val chatModelId by viewModel.chatModelId.collectAsState()
    val includeWebDefault by viewModel.includeWebDefault.collectAsState()
    val maxWebSearches by viewModel.maxWebSearches.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val testStatus by viewModel.testKeyStatus.collectAsState()
    val spendStats by viewModel.spendStats.collectAsState()

    val cleanStorageMessage by viewModel.cleanStorageMessage.collectAsState()
    val isCleaningStorage by viewModel.isCleaningStorage.collectAsState()
    val updateStatus by viewModel.updateStatus.collectAsState()

    val isInstagramLoggedIn by viewModel.isInstagramLoggedIn.collectAsState()
    val fetchTopComment by viewModel.fetchTopComment.collectAsState()

    val lastBackupAt by viewModel.lastBackupAt.collectAsState()
    val newRecipesSinceBackup by viewModel.newRecipesSinceBackup.collectAsState()
    val backupReminderThreshold by viewModel.backupReminderThreshold.collectAsState()
    val rememberBackupFolder by viewModel.rememberBackupFolder.collectAsState()
    val backupOpStatus by viewModel.backupOpStatus.collectAsState()
    val pendingRestore by viewModel.pendingRestoreInspection.collectAsState()

    val weekStartsOn by viewModel.weekStartsOn.collectAsState()
    val prepAlertEnabled by viewModel.prepAlertEnabled.collectAsState()
    val prepAlertTime by viewModel.prepAlertTime.collectAsState()
    val cookingAlertsEnabled by viewModel.cookingAlertsEnabled.collectAsState()
    val breakfastTime by viewModel.breakfastTime.collectAsState()
    val lunchTime by viewModel.lunchTime.collectAsState()
    val dinnerTime by viewModel.dinnerTime.collectAsState()

    var showPassword by remember { mutableStateOf(false) }

    // SAF Create Document launcher for Backup
    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            viewModel.backupToUri(uri)
        }
    }

    val pickBackupFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.onBackupFolderPicked(uri)
        }
    }

    // SAF Open Document launcher for Restore
    val openRestoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.inspectBackup(uri)
        }
    }

    // Restore Confirmation Dialog
    pendingRestore?.let { inspection ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissRestoreDialog() },
            title = { Text("Restore Library?") },
            text = {
                Text(
                    "Replace all ${inspection.currentRecipeCount} recipes in library with " +
                        "${inspection.restoredRecipeCount} recipes from backup?\n\n" +
                        "An automatic safety backup of your current database will be saved before restore begins."
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmRestore() }) {
                    Text("Restore & Restart")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissRestoreDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Backup Operation Status Dialog
    when (val status = backupOpStatus) {
        is BackupOpStatus.InProgress -> {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text("Please wait") },
                text = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Text(status.message)
                    }
                }
            )
        }
        is BackupOpStatus.Success -> {
            AlertDialog(
                onDismissRequest = { viewModel.clearBackupOpStatus() },
                title = { Text("Success") },
                text = { Text(status.message) },
                confirmButton = {
                    Button(onClick = { viewModel.clearBackupOpStatus() }) {
                        Text("OK")
                    }
                }
            )
        }
        is BackupOpStatus.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.clearBackupOpStatus() },
                title = { Text("Error") },
                text = { Text(status.message) },
                confirmButton = {
                    Button(onClick = { viewModel.clearBackupOpStatus() }) {
                        Text("OK")
                    }
                }
            )
        }
        BackupOpStatus.Idle -> Unit
    }

    // Storage Clean Result Dialog
    cleanStorageMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearCleanStorageMessage() },
            title = { Text("Storage Cleanup") },
            text = { Text(msg) },
            confirmButton = {
                Button(onClick = { viewModel.clearCleanStorageMessage() }) {
                    Text("OK")
                }
            }
        )
    }

    // Update Check & Install Dialogs
    when (val status = updateStatus) {
        is UpdateUiStatus.Checking -> {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text("Checking for Updates") },
                text = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Text("Connecting to GitHub Releases...")
                    }
                }
            )
        }
        is UpdateUiStatus.NoUpdate -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissUpdateStatus() },
                title = { Text("Up to Date") },
                text = { Text("You are on the latest version (${status.currentVersion}).") },
                confirmButton = {
                    Button(onClick = { viewModel.dismissUpdateStatus() }) {
                        Text("OK")
                    }
                }
            )
        }
        is UpdateUiStatus.UpdateAvailable -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissUpdateStatus() },
                title = { Text("Update Available: ${status.result.latestVersion}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(status.result.releaseName, fontWeight = FontWeight.Bold)
                        if (status.result.releaseNotes.isNotBlank()) {
                            Text(
                                text = status.result.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 8
                            )
                        }
                        if (status.result.appApkUrl == null) {
                            Text(
                                "No APK asset attached to this release.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                confirmButton = {
                    if (status.result.appApkUrl != null) {
                        Button(onClick = { viewModel.downloadAndInstallUpdate(status.result.appApkUrl) }) {
                            Text("Download & Install")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissUpdateStatus() }) {
                        Text("Later")
                    }
                }
            )
        }
        is UpdateUiStatus.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text("Downloading Update...") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val percent = (status.progress * 100).toInt()
                        LinearProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("$percent%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            )
        }
        is UpdateUiStatus.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissUpdateStatus() },
                title = { Text("Update Error") },
                text = { Text(status.message) },
                confirmButton = {
                    Button(onClick = { viewModel.dismissUpdateStatus() }) {
                        Text("OK")
                    }
                }
            )
        }
        UpdateUiStatus.Idle -> Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Anthropic API Key Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Anthropic API Key",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Your API key is stored securely in AndroidKeyStore with AES-GCM encryption.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { viewModel.updateApiKeyText(it) },
                    label = { Text("Anthropic API Key") },
                    placeholder = { Text("sk-ant-api03-...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "Hide" else "Show")
                        }
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.saveApiKey(apiKey) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Key")
                    }
                    OutlinedButton(
                        onClick = { viewModel.testApiKey() },
                        modifier = Modifier.weight(1f),
                        enabled = testStatus !is TestKeyStatus.Testing
                    ) {
                        if (testStatus is TestKeyStatus.Testing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Test Key")
                    }
                }

                when (val status = testStatus) {
                    is TestKeyStatus.Success -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(status.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is TestKeyStatus.Error -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> Unit
                }
            }



            // Theme Preference
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = theme == SettingsStore.THEME_DARK,
                        onClick = { viewModel.setTheme(SettingsStore.THEME_DARK) },
                        label = { Text("Dark") }
                    )
                    FilterChip(
                        selected = theme == SettingsStore.THEME_LIGHT,
                        onClick = { viewModel.setTheme(SettingsStore.THEME_LIGHT) },
                        label = { Text("Light") }
                    )
                    FilterChip(
                        selected = theme == SettingsStore.THEME_SYSTEM,
                        onClick = { viewModel.setTheme(SettingsStore.THEME_SYSTEM) },
                        label = { Text("System") }
                    )
                }
            }

            // Planner & Reminders
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Planner & Reminders",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text("Week starts on:", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = weekStartsOn.equals("Monday", ignoreCase = true),
                            onClick = { viewModel.setWeekStartsOn("Monday") },
                            label = { Text("Monday") }
                        )
                        FilterChip(
                            selected = weekStartsOn.equals("Sunday", ignoreCase = true),
                            onClick = { viewModel.setWeekStartsOn("Sunday") },
                            label = { Text("Sunday") }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Night-before prep alerts", style = MaterialTheme.typography.bodyMedium)
                            Text("Alert at $prepAlertTime for prep marked night before", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = prepAlertEnabled,
                            onCheckedChange = { viewModel.setPrepAlertEnabled(it) }
                        )
                    }

                    if (prepAlertEnabled) {
                        OutlinedTextField(
                            value = prepAlertTime,
                            onValueChange = { viewModel.setPrepAlertTime(it) },
                            label = { Text("Prep Alert Time (HH:mm)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Start-cooking alerts", style = MaterialTheme.typography.bodyMedium)
                            Text("Alerts before meal time minus recipe cooking time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = cookingAlertsEnabled,
                            onCheckedChange = { viewModel.setCookingAlertsEnabled(it) }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Default Meal Times",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Default times when generating new daily meal slots.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = breakfastTime,
                                onValueChange = { viewModel.setBreakfastTime(it) },
                                label = { Text("Breakfast", maxLines = 1) },
                                placeholder = { Text("08:30") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = lunchTime,
                                onValueChange = { viewModel.setLunchTime(it) },
                                label = { Text("Lunch", maxLines = 1) },
                                placeholder = { Text("13:00") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = dinnerTime,
                                onValueChange = { viewModel.setDinnerTime(it) },
                                label = { Text("Dinner", maxLines = 1) },
                                placeholder = { Text("19:30") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }
                }
            }

            // Chat & Web Search Settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "AI Chat & Web Search",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Recipe extraction is internally optimized and fixed to Claude Haiku 5.5 for rapid, cost-effective parsing. Choose the model for interactive recipe chat below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    var modelExpanded by remember { mutableStateOf(false) }
                    val currentChatModelLabel = when (chatModelId) {
                        SettingsStore.CHAT_MODEL_SONNET -> "Claude Sonnet 5.5"
                        else -> "Claude Haiku 5.5"
                    }

                    ExposedDropdownMenuBox(
                        expanded = modelExpanded,
                        onExpandedChange = { modelExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = currentChatModelLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Chat Model") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Claude Haiku 5.5") },
                                onClick = {
                                    viewModel.setChatModelId(SettingsStore.CHAT_MODEL_HAIKU)
                                    modelExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Claude Sonnet 5.5") },
                                onClick = {
                                    viewModel.setChatModelId(SettingsStore.CHAT_MODEL_SONNET)
                                    modelExpanded = false
                                }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Include web by default", style = MaterialTheme.typography.bodyMedium)
                            Text("Enables web recipe searches in chat by default", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = includeWebDefault,
                            onCheckedChange = { viewModel.setIncludeWebDefault(it) }
                        )
                    }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Max web searches per message: $maxWebSearches", style = MaterialTheme.typography.bodyMedium)
                        Text("Each search costs about US$0.01", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = maxWebSearches.toFloat(),
                            onValueChange = { viewModel.setMaxWebSearches(it.roundToInt()) },
                            valueRange = SettingsStore.MIN_WEB_SEARCHES.toFloat()..SettingsStore.MAX_WEB_SEARCHES.toFloat(),
                            steps = SettingsStore.MAX_WEB_SEARCHES - SettingsStore.MIN_WEB_SEARCHES - 1
                        )
                    }
                }
            }

            // Backup & Restore Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Backup & Restore",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Consistent single-file snapshots via SQLite VACUUM INTO, SHA-256 manifest, and STORED images.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Last Backup:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = lastBackupAt?.take(16)?.replace('T', ' ') ?: "Never",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("New Recipes Since Backup:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "$newRecipesSinceBackup",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (newRecipesSinceBackup >= backupReminderThreshold && backupReminderThreshold > 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Remember backup folder", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Pick a folder once; later backups save there in one tap",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = rememberBackupFolder,
                            onCheckedChange = { viewModel.setRememberBackupFolder(it) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Backup reminder threshold:", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = if (backupReminderThreshold == 0) "0" else "$backupReminderThreshold",
                            onValueChange = { input ->
                                val num = input.filter { it.isDigit() }.toIntOrNull() ?: 0
                                viewModel.setBackupReminderThreshold(num)
                            },
                            modifier = Modifier.width(90.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.backupNow(
                                    pickFile = { createBackupLauncher.launch(BackupManager.generateSuggestedFileName()) },
                                    pickFolder = { pickBackupFolderLauncher.launch(null) }
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Backup Now")
                        }
                        OutlinedButton(
                            onClick = {
                                openRestoreLauncher.launch(
                                    arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed", "*/*")
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Restore")
                        }
                    }
                }
            }

            // Instagram Integration Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Instagram Integration",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Log in to your Instagram account to enable fetching top comments where recipes are shared in comments instead of captions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Account Status:", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = if (isInstagramLoggedIn) "Logged in" else "Logged out",
                                fontWeight = FontWeight.Bold,
                                color = if (isInstagramLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isInstagramLoggedIn) {
                            OutlinedButton(onClick = { viewModel.logoutInstagram() }) {
                                Text("Log out")
                            }
                        } else {
                            Button(onClick = {
                                val intent = android.content.Intent(context, org.eu.angsumanray.recipecatcher.capture.LoginWebViewActivity::class.java)
                                context.startActivity(intent)
                            }) {
                                Text("Log in")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Fetch top comment", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = if (isInstagramLoggedIn) "Append first comment to caption during foreground captures" else "Requires Instagram login",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = fetchTopComment && isInstagramLoggedIn,
                            onCheckedChange = { viewModel.setFetchTopComment(it) },
                            enabled = isInstagramLoggedIn
                        )
                    }
                }
            }

            // Bulk Import Saves Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Import Saves",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Bulk import your saved Instagram posts, reels, and collections from a data export or pasted links.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onNavigateToImportSaves,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Import Saves")
                    }
                }
            }

            // List Manager Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Vocabularies & Lists",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Manage tags, diet allergens/contains, equipment, and consumables. Rename, delete, or merge existing items.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onNavigateToListManager,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Manage Lists")
                    }
                }
            }

            // Storage & Maintenance Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Storage & Updates",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Clean up orphaned recipe/cook photos unreferenced by your database, and check for new releases.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.cleanStorage() },
                            modifier = Modifier.weight(1f),
                            enabled = !isCleaningStorage
                        ) {
                            if (isCleaningStorage) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("Clean Storage")
                        }
                        Button(
                            onClick = { viewModel.checkForUpdates() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Check Updates")
                        }
                    }
                }
            }

            // AI Spend Panel
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AI Spend & Token Usage",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { viewModel.loadSpendStats() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }

                    Text("All captures are logged in llm_usage table.")

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Input Tokens:")
                        Text("${spendStats.inputTokens}", fontWeight = FontWeight.SemiBold)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Output Tokens:")
                        Text("${spendStats.outputTokens}", fontWeight = FontWeight.SemiBold)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Cache Read Tokens:")
                        Text("${spendStats.cacheReadTokens}", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    val formattedCost = String.format(Locale.US, "$%.4f", spendStats.estimatedCost)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total Estimated Cost:", fontWeight = FontWeight.Bold)
                        Text(formattedCost, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
