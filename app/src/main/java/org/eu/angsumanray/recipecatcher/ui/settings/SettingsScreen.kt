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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.eu.angsumanray.recipecatcher.backup.BackupManager
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToListManager: () -> Unit = {}
) {
    val apiKey by viewModel.apiKey.collectAsState()
    val modelId by viewModel.modelId.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val testStatus by viewModel.testKeyStatus.collectAsState()
    val spendStats by viewModel.spendStats.collectAsState()

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

            // Model ID Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Claude Model ID",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { viewModel.setModelId(it) },
                    label = { Text("Model ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
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
