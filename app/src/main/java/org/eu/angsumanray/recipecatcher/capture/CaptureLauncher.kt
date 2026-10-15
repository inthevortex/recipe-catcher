package org.eu.angsumanray.recipecatcher.capture

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts a capture that was just created from a shared or pasted link, without holding up the
 * screen that started it: the Instagram comment is read in the background, then extraction is queued.
 */
@Singleton
class CaptureLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recipeRepository: RecipeRepository,
    private val commentFetcher: CommentFetcher
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

    private companion object {
        const val TAG = "CaptureLauncher"

        /** Longer than [CommentFetcher]'s whole page-load and reading budget. */
        const val FALLBACK_DELAY_MILLIS = 60_000L
    }
}
