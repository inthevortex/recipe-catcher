package org.eu.angsumanray.recipecatcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eu.angsumanray.recipecatcher.capture.CaptureWorker
import org.eu.angsumanray.recipecatcher.capture.CommentFetcher
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.data.repo.CaptureStart
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject
    lateinit var recipeRepository: RecipeRepository

    @Inject
    lateinit var commentFetcher: CommentFetcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        val type = intent?.type

        if (action == Intent.ACTION_SEND && type?.startsWith("image/") == true) {
            val uri = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.getItemAt(0)?.uri
            if (uri != null) {
                lifecycleScope.launch {
                    handleSharedImages(listOf(uri))
                }
                return
            }
        } else if (action == Intent.ACTION_SEND_MULTIPLE && type?.startsWith("image/") == true) {
            val uris = mutableListOf<Uri>()
            val extraList = androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (!extraList.isNullOrEmpty()) {
                uris.addAll(extraList)
            } else {
                val clipData = intent.clipData
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        clipData.getItemAt(i).uri?.let { uris.add(it) }
                    }
                }
            }
            if (uris.isNotEmpty()) {
                lifecycleScope.launch {
                    handleSharedImages(uris)
                }
                return
            }
        }

        val text = when (action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }

        if (text.isNullOrBlank()) {
            Toast.makeText(this, "No link or image found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            handleSharedText(text)
        }
    }

    private suspend fun handleSharedImages(uris: List<Uri>) = withContext(Dispatchers.IO) {
        val sourceKey = "image_${UUID.randomUUID()}"
        val cacheDir = File(cacheDir, "image_captures/$sourceKey").apply { mkdirs() }
        val savedFiles = mutableListOf<File>()

        for ((index, uri) in uris.withIndex()) {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val file = File(cacheDir, "img_${index + 1}.jpg")
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                    if (file.length() > 0) savedFiles.add(file)
                }
            } catch (_: Exception) {
            }
        }

        if (savedFiles.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ShareReceiverActivity, "Could not read shared photo", Toast.LENGTH_SHORT).show()
                finish()
            }
            return@withContext
        }

        val recipeId = recipeRepository.insertPendingImageRecipe(
            sourceKey = sourceKey,
            imageDirectoryOrPaths = cacheDir.absolutePath
        )

        CaptureWorker.enqueue(applicationContext, recipeId)

        withContext(Dispatchers.Main) {
            Toast.makeText(this@ShareReceiverActivity, "Scanning recipe photos...", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this@ShareReceiverActivity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_PENDING_RECIPE_ID, recipeId)
            })
            finish()
        }
    }

    private suspend fun handleSharedText(text: String) {
        when (val start = recipeRepository.startCapture(text)) {
            CaptureStart.NoLink -> {
                Toast.makeText(this, "No link found", Toast.LENGTH_SHORT).show()
            }
            is CaptureStart.Existing -> {
                Toast.makeText(this, "Already saved", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_RECIPE_ID, start.recipeId)
                })
            }
            is CaptureStart.Created -> {
                Toast.makeText(this, "Capturing recipe...", Toast.LENGTH_SHORT).show()
                try {
                    if (start.sourceType == SourceType.INSTAGRAM) {
                        commentFetcher.fetchTopComment(text)?.let { recipeRepository.updateCommentRaw(start.recipeId, it) }
                    }
                } finally {
                    // The capture starts even when the comment fetch is cut short.
                    CaptureWorker.enqueue(applicationContext, start.recipeId)
                }
                startActivity(Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_PENDING_RECIPE_ID, start.recipeId)
                })
            }
        }
        finish()
    }
}
