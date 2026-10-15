package org.eu.angsumanray.recipecatcher.wear.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val Context.todayDataStore: DataStore<Preferences> by preferencesDataStore(name = "wear_today_prefs")

class TodayRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _mealsFlow = MutableStateFlow<List<WearMealItem>>(emptyList())
    val mealsFlow: StateFlow<List<WearMealItem>> = _mealsFlow.asStateFlow()

    private val _pendingDoneIds = MutableStateFlow<Set<Long>>(emptySet())
    val pendingDoneIds: StateFlow<Set<Long>> = _pendingDoneIds.asStateFlow()

    init {
        scope.launch {
            loadCachedData()
        }
    }

    private suspend fun loadCachedData() {
        val prefs = context.todayDataStore.data.first()
        val pending = prefs[KEY_PENDING_DONE]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        _pendingDoneIds.value = pending

        val jsonStr = prefs[KEY_SNAPSHOT_JSON]
        if (!jsonStr.isNullOrBlank()) {
            try {
                val snapshot = json.decodeFromString<WearTodaySnapshot>(jsonStr)
                applySnapshot(snapshot, pending)
            } catch (e: Exception) {
                // Ignore parse errors on corrupt cache
            }
        }
    }

    private fun applySnapshot(snapshot: WearTodaySnapshot, pendingDone: Set<Long>) {
        _mealsFlow.value = snapshot.items.map { item ->
            if (pendingDone.contains(item.id)) {
                item.copy(done = true)
            } else {
                item
            }
        }
    }

    suspend fun updateFromSnapshot(snapshot: WearTodaySnapshot) = withContext(Dispatchers.IO) {
        val jsonStr = json.encodeToString(WearTodaySnapshot.serializer(), snapshot)
        context.todayDataStore.edit { prefs ->
            prefs[KEY_SNAPSHOT_JSON] = jsonStr
        }
        val pending = _pendingDoneIds.value
        applySnapshot(snapshot, pending)
    }

    suspend fun toggleMeal(mealId: Long) = withContext(Dispatchers.IO) {
        // Optimistic update
        val current = _mealsFlow.value
        val updated = current.map { item ->
            if (item.id == mealId) item.copy(done = !item.done) else item
        }
        _mealsFlow.value = updated

        // Enqueue to pending
        val newPending = _pendingDoneIds.value + mealId
        _pendingDoneIds.value = newPending
        context.todayDataStore.edit { prefs ->
            prefs[KEY_PENDING_DONE] = newPending.map { it.toString() }.toSet()
        }

        // Try to deliver immediately
        sendDoneMessage(mealId)
    }

    suspend fun sendDoneMessage(mealId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val nodeClient = Wearable.getNodeClient(context)
            val nodes = nodeClient.connectedNodes.await()
            if (nodes.isEmpty()) return@withContext false

            val messageClient = Wearable.getMessageClient(context)
            val payload = mealId.toString().toByteArray()
            var delivered = false

            for (node in nodes) {
                messageClient.sendMessage(node.id, PATH_DONE, payload).await()
                delivered = true
            }

            if (delivered) {
                val newPending = _pendingDoneIds.value - mealId
                _pendingDoneIds.value = newPending
                context.todayDataStore.edit { prefs ->
                    prefs[KEY_PENDING_DONE] = newPending.map { it.toString() }.toSet()
                }
            }
            delivered
        } catch (e: Exception) {
            false
        }
    }

    suspend fun flushPendingTicks() = withContext(Dispatchers.IO) {
        val pending = _pendingDoneIds.value
        if (pending.isEmpty()) return@withContext

        for (id in pending) {
            sendDoneMessage(id)
        }
    }

    fun getMeals(): List<WearMealItem> = _mealsFlow.value

    companion object {
        val KEY_SNAPSHOT_JSON = stringPreferencesKey("snapshot_json")
        val KEY_PENDING_DONE = stringSetPreferencesKey("pending_done_ids")
        const val PATH_DONE = "/done"

        @Volatile
        private var INSTANCE: TodayRepository? = null

        fun getInstance(context: Context): TodayRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TodayRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
