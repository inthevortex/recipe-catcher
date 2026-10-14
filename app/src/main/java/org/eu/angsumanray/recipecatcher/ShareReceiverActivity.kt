package org.eu.angsumanray.recipecatcher

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.capture.CaptureWorker
import org.eu.angsumanray.recipecatcher.core.capture.UrlNormalizer
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import javax.inject.Inject

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject
    lateinit var recipeRepository: RecipeRepository

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

        val url = UrlNormalizer.extractUrl(text)
        if (url == null) {
            Toast.makeText(this, "No link found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            handleCapturedUrl(url)
        }
    }

    private suspend fun handleCapturedUrl(rawUrl: String) {
        val shortcode = UrlNormalizer.extractInstagramShortcode(rawUrl)
        val sourceKey: String
        val sourceType: String
        val canonicalUrl: String?

        if (shortcode != null) {
            sourceType = "instagram"
            sourceKey = shortcode
            canonicalUrl = UrlNormalizer.canonicalizeInstagramUrl(shortcode)
        } else {
            sourceType = "web"
            val normalized = UrlNormalizer.normalizeWebUrl(rawUrl)
            sourceKey = normalized
            canonicalUrl = normalized
        }

        // Dedupe check
        val existing = recipeRepository.getRecipeBySourceKey(sourceKey)
        if (existing != null) {
            Toast.makeText(this, "Already saved!", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("recipe_id", existing.id)
            }
            startActivity(intent)
            finish()
            return
        }

        // Insert pending row and enqueue capture
        val recipeId = recipeRepository.insertPendingRecipe(
            sourceType = sourceType,
            sourceKey = sourceKey,
            sourceUrl = canonicalUrl
        )

        CaptureWorker.enqueue(applicationContext, recipeId)

        Toast.makeText(this, "Capturing recipe...", Toast.LENGTH_SHORT).show()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("pending_recipe_id", recipeId)
        }
        startActivity(intent)
        finish()
    }
}
