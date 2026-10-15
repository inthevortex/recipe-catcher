package org.eu.angsumanray.recipecatcher.ui.paste

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.capture.CaptureLauncher
import org.eu.angsumanray.recipecatcher.capture.CaptureWorker
import org.eu.angsumanray.recipecatcher.core.capture.SourceClassifier
import org.eu.angsumanray.recipecatcher.core.capture.UrlNormalizer
import org.eu.angsumanray.recipecatcher.data.repo.CaptureStart
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteLinkScreen(
    recipeRepository: RecipeRepository,
    captureLauncher: CaptureLauncher? = null,
    onNavigateBack: () -> Unit,
    onRecipeCaptured: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var urlText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    // Android returns no clip until the window has input focus, which comes after first composition.
    val isWindowFocused = LocalWindowInfo.current.isWindowFocused
    var clipboardChecked by remember { mutableStateOf(false) }
    LaunchedEffect(isWindowFocused) {
        if (!isWindowFocused || clipboardChecked) return@LaunchedEffect
        clipboardChecked = true
        if (urlText.isNotBlank()) return@LaunchedEffect
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
            SourceClassifier.classifySharedText(text)?.let { urlText = it.canonicalUrl }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paste Link") },
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
                .padding(24.dp)
        ) {
            Text(
                text = "Capture Recipe from Link",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Paste an Instagram post or reel, YouTube video or short, or any recipe website link.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = urlText,
                onValueChange = {
                    urlText = it
                    errorMessage = null
                },
                label = { Text("Recipe URL") },
                placeholder = { Text("https://... (Instagram, YouTube, or recipe website)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = errorMessage != null
            )

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = clipboard?.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0).text?.toString().orEmpty()
                        val extracted = UrlNormalizer.extractUrl(text) ?: text
                        urlText = extracted.trim()
                        errorMessage = null
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Paste from Clipboard")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val trimmed = urlText.trim()
                    if (trimmed.isEmpty()) {
                        errorMessage = "Please enter or paste a URL"
                        return@Button
                    }
                    isCapturing = true
                    scope.launch {
                        try {
                            when (val start = recipeRepository.startCapture(trimmed)) {
                                CaptureStart.NoLink -> {
                                    errorMessage = "That doesn't look like a link"
                                    isCapturing = false
                                }
                                is CaptureStart.Existing -> onRecipeCaptured(start.recipeId)
                                is CaptureStart.Created -> {
                                    if (captureLauncher != null) {
                                        captureLauncher.start(start.recipeId, start.sourceType, trimmed)
                                    } else {
                                        CaptureWorker.enqueue(context, start.recipeId)
                                    }
                                    onRecipeCaptured(start.recipeId)
                                }
                            }
                        } catch (e: Exception) {
                            errorMessage = e.message ?: "Failed to start capture"
                            isCapturing = false
                        }
                    }
                },
                enabled = !isCapturing && urlText.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isCapturing) "Starting capture..." else "Capture Recipe")
            }
        }
    }
}
