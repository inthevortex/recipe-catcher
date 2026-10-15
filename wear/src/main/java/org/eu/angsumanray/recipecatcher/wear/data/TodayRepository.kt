package org.eu.angsumanray.recipecatcher.wear.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import java.time.LocalDate

private val Context.todayDataStore: DataStore<Preferences> by preferencesDataStore(name = "wear_today_prefs")

/**
 * The watch's copy of the phone's Today list plus a queue of ticks not yet delivered. Both live in
 * DataStore, so a tick made out of range survives a watch reboot and is resent on reconnect.
 */
class TodayRepository(
    private val context: Context,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val sender: suspend (Long) -> Boolean = { entryId -> sendDoneToPhone(context, entryId) }
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var snapshot: WearTodaySnapshot? = null

    private val _mealsFlow = MutableStateFlow<List<WearMealItem>>(emptyList())
    val mealsFlow: StateFlow<List<WearMealItem>> = _mealsFlow.asStateFlow()

    private val _pendingDoneIds = MutableStateFlow<Set<Long>>(emptySet())
    val pendingDoneIds: StateFlow<Set<Long>> = _pendingDoneIds.asStateFlow()

    private val loaded = scope.async { loadCachedData() }

    private suspend fun loadCachedData() {
        val prefs = context.todayDataStore.data.first()
        val pending = prefs[KEY_PENDING_DONE]?.mapNotNull { it.toLongOrNull() }?.toSet().orEmpty()
        val cached = prefs[KEY_SNAPSHOT_JSON]?.let { raw ->
            try {
                json.decodeFromString<WearTodaySnapshot>(raw)
            } catch (e: Exception) {
                Log.w(TAG, "Ignoring unreadable cached snapshot", e)
                null
            }
        }
        mutex.withLock {
            _pendingDoneIds.value = pending
            snapshot = cached
            publishMeals()
        }
    }

    /** Today's items with undelivered ticks shown as done; empty when the cached list is from another day. */
    suspend fun meals(): List<WearMealItem> {
        loaded.await()
        return _mealsFlow.value
    }

    suspend fun updateFromSnapshot(newSnapshot: WearTodaySnapshot) {
        loaded.await()
        mutex.withLock {
            snapshot = newSnapshot
            // A tick the phone now reports as done has been applied, so it can leave the queue.
            val confirmed = newSnapshot.items.filter { it.done }.map { it.id }.toSet()
            val pending = _pendingDoneIds.value - confirmed
            _pendingDoneIds.value = pending
            context.todayDataStore.edit { prefs ->
                prefs[KEY_SNAPSHOT_JSON] = json.encodeToString(WearTodaySnapshot.serializer(), newSnapshot)
                prefs[KEY_PENDING_DONE] = pending.map { it.toString() }.toSet()
            }
            publishMeals()
        }
    }

    /**
     * Ticks [entryId] off. The watch only ever marks done, because a queued tick may reach the
     * phone more than once and "done" is safe to repeat. Delivery happens in the background.
     */
    suspend fun markDone(entryId: Long) {
        loaded.await()
        mutex.withLock {
            if (_mealsFlow.value.any { it.id == entryId && it.done }) return
            val pending = _pendingDoneIds.value + entryId
            _pendingDoneIds.value = pending
            context.todayDataStore.edit { prefs ->
                prefs[KEY_PENDING_DONE] = pending.map { it.toString() }.toSet()
            }
            publishMeals()
        }
        scope.launch { flushPendingTicks() }
    }

    /** Sends every queued tick; ones that can't be delivered stay queued for the next try. */
    suspend fun flushPendingTicks() {
        loaded.await()
        for (entryId in _pendingDoneIds.value) {
            val delivered = try {
                sender(entryId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Tick for entry $entryId not delivered yet", e)
                false
            }
            if (delivered) {
                mutex.withLock {
                    val pending = _pendingDoneIds.value - entryId
                    _pendingDoneIds.value = pending
                    context.todayDataStore.edit { prefs ->
                        prefs[KEY_PENDING_DONE] = pending.map { it.toString() }.toSet()
                    }
                }
            }
        }
    }

    private fun publishMeals() {
        val current = snapshot?.takeIf { it.dateIso == today().toString() }
        val pending = _pendingDoneIds.value
        _mealsFlow.value = current?.items.orEmpty().map { item ->
            if (item.id in pending) item.copy(done = true) else item
        }
    }

    companion object {
        private const val TAG = "TodayRepository"
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

        /** True once a connected phone has accepted the message. */
        private suspend fun sendDoneToPhone(context: Context, entryId: Long): Boolean {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: return false
            Wearable.getMessageClient(context)
                .sendMessage(phone.id, PATH_DONE, entryId.toString().toByteArray())
                .await()
            return true
        }
    }
}
