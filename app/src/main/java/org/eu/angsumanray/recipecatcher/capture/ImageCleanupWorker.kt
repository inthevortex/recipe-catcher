package org.eu.angsumanray.recipecatcher.capture

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import java.util.concurrent.TimeUnit

@HiltWorker
class ImageCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val recipeRepository: RecipeRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ImageCleanupWorker"
        private const val PERIODIC_WORK_NAME = "image_cleanup_periodic"
        private const val ONE_TIME_WORK_NAME = "image_cleanup_once"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ImageCleanupWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun scheduleWeekly(context: Context) {
            val request = PeriodicWorkRequestBuilder<ImageCleanupWorker>(7, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val deleted = recipeRepository.cleanOrphanedImages()
            Log.d(TAG, "Image cleanup completed: deleted $deleted orphaned files")
            Result.success(workDataOf("deleted_count" to deleted))
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning orphaned images", e)
            Result.failure()
        }
    }
}
