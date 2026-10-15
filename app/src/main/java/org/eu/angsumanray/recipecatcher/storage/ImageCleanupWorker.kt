package org.eu.angsumanray.recipecatcher.storage

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
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

/**
 * Scans filesDir/images/ and filesDir/images/cooked/, compares against database references
 * (recipe.cover_image_path / cover_photo_path, recipe.image_path, and cook_log.photo_path),
 * and deletes unreferenced orphaned files, reporting deleted count and freed bytes.
 */
@HiltWorker
class ImageCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val recipeRepository: RecipeRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val result = recipeRepository.cleanOrphanedImagesWithStats()
            Log.d(TAG, "Image cleanup completed: deleted ${result.deletedCount} orphaned files, freed ${result.bytesFreed} bytes")
            Result.success(
                workDataOf(
                    KEY_DELETED_COUNT to result.deletedCount,
                    KEY_BYTES_FREED to result.bytesFreed
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning orphaned images", e)
            Result.failure()
        }
    }

    companion object {
        const val TAG = "ImageCleanupWorker"
        const val PERIODIC_WORK_NAME = "image_cleanup_periodic"
        const val ONE_TIME_WORK_NAME = "image_cleanup_once"
        const val KEY_DELETED_COUNT = "deleted_count"
        const val KEY_BYTES_FREED = "bytes_freed"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ImageCleanupWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun scheduleWeekly(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<ImageCleanupWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
