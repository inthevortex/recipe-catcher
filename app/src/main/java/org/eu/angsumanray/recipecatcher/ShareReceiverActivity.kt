package org.eu.angsumanray.recipecatcher

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.capture.CaptureWorker
import org.eu.angsumanray.recipecatcher.capture.CommentFetcher
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.data.repo.CaptureStart
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import javax.inject.Inject

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject
    lateinit var recipeRepository: RecipeRepository

    @Inject
    lateinit var commentFetcher: CommentFetcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }

        if (text.isNullOrBlank()) {
            Toast.makeText(this, "No link found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            handleSharedText(text)
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
