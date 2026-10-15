package org.eu.angsumanray.recipecatcher.capture

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.core.capture.UrlNormalizer
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts and retries captures without holding up the screen that asked. The slow work, including
 * reading an Instagram recipe comment, runs in [CaptureWorker], which survives the app being closed.
 */
@Singleton
class CaptureLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recipeRepository: RecipeRepository,
    private val foregroundPageFetcher: ForegroundPageFetcher
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** [sharedText] is what the user shared or pasted; it may hold a comment link. */
    fun start(recipeId: Long, sourceType: SourceType, sharedText: String) {
        CaptureWorker.enqueue(context, recipeId, commentSource = sharedText.takeIf { sourceType == SourceType.INSTAGRAM })
    }

    /**
     * Retries or restarts a capture. The row is marked in progress at once, so the UI shows a
     * loader while a blocked web page is reloaded or the capture is queued again.
     */
    fun retry(recipeId: Long) {
        scope.launch {
            val stored = recipeRepository.getRecipe(recipeId) ?: return@launch
            val recipe = withoutLinkCaption(stored)
            recipeRepository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_PENDING_FETCH, null)

            if (recipe.sourceType == SourceType.WEB.value && stored.errorMessage == CaptureWorker.BLOCKED_MESSAGE) {
                if (foregroundPageFetcher.retryInWebView(recipeId).isSuccess) return@launch
            }

            val nextStatus = if (recipe.captionRaw.isNullOrBlank()) {
                RecipeEntity.STATUS_PENDING_FETCH
            } else {
                RecipeEntity.STATUS_PENDING_EXTRACT
            }
            recipeRepository.updateRecipeStatus(recipeId, nextStatus, null)
            val commentSource = if (recipe.sourceType == SourceType.INSTAGRAM.value) {
                recipe.sourceUrl ?: recipe.sourceKey?.let { UrlNormalizer.canonicalizeInstagramUrl(it) }
            } else {
                null
            }
            CaptureWorker.enqueue(context, recipeId, commentSource)
        }
    }

    /** An earlier paste could store a bare Instagram link as the caption; clear it so the post is fetched again. */
    private suspend fun withoutLinkCaption(recipe: RecipeEntity): RecipeEntity {
        if (recipe.captionRaw == null || !UrlNormalizer.isInstagramPostLink(recipe.captionRaw)) return recipe
        val cleared = recipe.copy(captionRaw = null, updatedAt = System.currentTimeMillis())
        recipeRepository.updateRecipe(cleared)
        return cleared
    }
}
