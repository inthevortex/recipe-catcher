package org.eu.angsumanray.recipecatcher.wear

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
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
            val today = LocalDate.now()
            val slots = plannerDao.getSlotsForDate(today)
            val entries = plannerDao.getEntriesForDate(today)
            val slotMap = slots.associateBy { it.id }

            val items = entries.map { entry ->
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

            val snapshot = WearTodaySnapshot(
                dateIso = today.toString(),
                timestamp = System.currentTimeMillis(),
                items = items
            )
            val encodedJson = json.encodeToString(WearTodaySnapshot.serializer(), snapshot)

            val request = PutDataMapRequest.create(PATH_TODAY).apply {
                dataMap.putString(KEY_MEALS_JSON, encodedJson)
                dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            }
            val putDataReq = request.asPutDataRequest().setUrgent()
            val dataClient = Wearable.getDataClient(context)
            dataClient.putDataItem(putDataReq).await()
            true
        } catch (e: Exception) {
            // Wearable API may throw if Google Play Services is unavailable or not running on device
            false
        }
    }

    companion object {
        const val PATH_TODAY = "/today"
        const val KEY_MEALS_JSON = "meals_json"
        const val KEY_TIMESTAMP = "timestamp"
    }
}
