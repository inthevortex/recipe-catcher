package org.eu.angsumanray.recipecatcher.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.data.db.dao.PlannerDao
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanEntryEntity
import org.eu.angsumanray.recipecatcher.data.repo.PlannerRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Wire format shared with the watch; keep it in step with `WearMealItem` in `:wear`. */
@Serializable
data class WearMealItem(
    val id: Long,
    val title: String,
    val kind: String,
    val slot: String,
    val time: String? = null,
    val done: Boolean = false
)

@Serializable
data class WearTodaySnapshot(
    val dateIso: String,
    val timestamp: Long,
    val items: List<WearMealItem> = emptyList()
)

/**
 * Publishes the Today snapshot to connected Wear OS watches over Google Play Services Wearable Data Layer.
 */
@Singleton
class WearSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val plannerDao: PlannerDao,
    private val plannerRepository: PlannerRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun publishToday(): Boolean = withContext(Dispatchers.IO) {
        try {
            val snapshot = buildSnapshot(LocalDate.now(), System.currentTimeMillis())
            val request = PutDataMapRequest.create(PATH_TODAY).apply {
                dataMap.putString(KEY_MEALS_JSON, json.encodeToString(WearTodaySnapshot.serializer(), snapshot))
                // A fresh value every time, so the Data Layer sees a change even when the list is unchanged.
                dataMap.putLong(KEY_TIMESTAMP, snapshot.timestamp)
            }
            Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()).await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Play Services may be missing, or no watch was ever paired.
            Log.d(TAG, "Today snapshot not published to the watch", e)
            false
        }
    }

    /** The Today list the watch shows: [date]'s entries in slot order. */
    suspend fun buildSnapshot(date: LocalDate, now: Long): WearTodaySnapshot {
        val slotMap = plannerDao.getSlotsForDate(date).associateBy { it.id }
        val items = plannerDao.getEntriesForDate(date).map { entry ->
            val slot = slotMap[entry.slotId]
            val recipe = entry.recipeId?.let { plannerRepository.getRecipe(it) }
            val title = recipe?.title ?: entry.text
                ?: if (entry.kind == PlanEntryEntity.KIND_PREP) "Prep" else "Meal"
            WearMealItem(
                id = entry.id,
                title = title,
                kind = entry.kind,
                slot = slot?.name ?: "Meal",
                time = slot?.time?.toString(),
                done = entry.doneAt != null
            )
        }
        return WearTodaySnapshot(dateIso = date.toString(), timestamp = now, items = items)
    }

    companion object {
        const val PATH_TODAY = "/today"
        const val KEY_MEALS_JSON = "meals_json"
        const val KEY_TIMESTAMP = "timestamp"
        private const val TAG = "WearSync"
    }
}
