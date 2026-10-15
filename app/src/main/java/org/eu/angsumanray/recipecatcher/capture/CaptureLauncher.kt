package org.eu.angsumanray.recipecatcher.capture

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.core.capture.UrlNormalizer
import org.eu.angsumanray.recipecatcher.core.insta.InstagramComments
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts and retries captures without holding up the screen that asked: slow page and comment
 * reads run in the background, outliving that screen, and extraction is queued once they finish.
 */
@Singleton
class CaptureLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recipeRepository: RecipeRepository,
    private val commentFetcher: CommentFetcher,
    private val foregroundPageFetcher: ForegroundPageFetcher
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** [sharedText] is what the user shared or pasted; it may hold a comment link. */
    fun start(recipeId: Long, sourceType: SourceType, sharedText: String) {
        if (sourceType != SourceType.INSTAGRAM) {
            CaptureWorker.enqueue(context, recipeId)
            return
        }
        // Runs the capture anyway if the process dies before the comment is read.
        CaptureWorker.enqueue(context, recipeId, initialDelayMillis = FALLBACK_DELAY_MILLIS)
        scope.launch {
            try {
                commentFetcher.fetchTopComment(sharedText)?.let { recipeRepository.updateCommentRaw(recipeId, it) }
            } catch (e: Exception) {
                Log.w(TAG, "Comment fetch failed for recipe $recipeId", e)
            } finally {
                CaptureWorker.enqueue(context, recipeId)
            }
        }
    }

    /**
     * Retries a failed capture. The row is marked in progress at once, so the UI shows a loader
     * while a blocked web page is reloaded or the Instagram comment is read again.
     */
    fun retry(recipeId: Long) {
        scope.launch {
            val stored = recipeRepository.getRecipe(recipeId) ?: return@launch
            val recipe = withoutLinkCaption(stored)
            recipeRepository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_PENDING_FETCH, null)

            if (recipe.sourceType == SourceType.WEB.value && stored.errorMessage == CaptureWorker.BLOCKED_MESSAGE) {
                if (foregroundPageFetcher.retryInWebView(recipeId).isSuccess) return@launch
            }
            if (recipe.sourceType == SourceType.INSTAGRAM.value) {
                CaptureWorker.enqueue(context, recipeId, initialDelayMillis = FALLBACK_DELAY_MILLIS)
                refreshComment(recipe)
            }

            val nextStatus = if (recipe.captionRaw.isNullOrBlank()) {
                RecipeEntity.STATUS_PENDING_FETCH
            } else {
                RecipeEntity.STATUS_PENDING_EXTRACT
            }
            recipeRepository.updateRecipeStatus(recipeId, nextStatus, null)
            CaptureWorker.enqueue(context, recipeId)
        }
    }

    /** Rereads the recipe comment; a stored comment that is only a username is dropped if none is found. */
    private suspend fun refreshComment(recipe: RecipeEntity) {
        val url = recipe.sourceUrl ?: recipe.sourceKey?.let { UrlNormalizer.canonicalizeInstagramUrl(it) } ?: return
        val fetched = try {
            commentFetcher.fetchTopComment(url, recipe.captionRaw)
        } catch (e: Exception) {
            Log.w(TAG, "Comment fetch failed for recipe ${recipe.id}", e)
            null
        }
        when {
            fetched != null -> recipeRepository.updateCommentRaw(recipe.id, fetched)
            recipe.commentRaw != null && !InstagramComments.looksLikeCommentText(recipe.commentRaw) ->
                recipeRepository.updateCommentRaw(recipe.id, null)
        }
    }

    /** An earlier paste could store a bare Instagram link as the caption; clear it so the post is fetched again. */
    private suspend fun withoutLinkCaption(recipe: RecipeEntity): RecipeEntity {
        if (recipe.captionRaw == null || !UrlNormalizer.isInstagramPostLink(recipe.captionRaw)) return recipe
        val cleared = recipe.copy(captionRaw = null, updatedAt = System.currentTimeMillis())
        recipeRepository.updateRecipe(cleared)
        return cleared
    }

    private companion object {
        const val TAG = "CaptureLauncher"

        /** Longer than [CommentFetcher]'s whole page-load and reading budget. */
        const val FALLBACK_DELAY_MILLIS = 60_000L
    }
}
