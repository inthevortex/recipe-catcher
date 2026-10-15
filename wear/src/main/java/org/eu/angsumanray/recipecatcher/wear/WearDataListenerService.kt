package org.eu.angsumanray.recipecatcher.wear

import android.content.ComponentName
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.wear.complication.TodayComplicationService
import org.eu.angsumanray.recipecatcher.wear.data.TodayRepository
import org.eu.angsumanray.recipecatcher.wear.data.WearTodaySnapshot
import org.eu.angsumanray.recipecatcher.wear.tile.TodayTileService

/**
 * Receives the phone's Today snapshot. Callbacks run on a Play Services binder thread, so they
 * block until the work is done instead of launching coroutines the unbinding service would cancel.
 */
class WearDataListenerService : WearableListenerService() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // The buffer is released when this returns, so read the payloads first.
        val payloads = dataEvents
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == PATH_TODAY }
            .mapNotNull { DataMapItem.fromDataItem(it.dataItem).dataMap.getString(KEY_MEALS_JSON) }
        val latest = payloads.lastOrNull() ?: return

        runBlocking {
            try {
                val repository = TodayRepository.getInstance(applicationContext)
                repository.updateFromSnapshot(json.decodeFromString<WearTodaySnapshot>(latest))
                refreshSurfaces()
                // The phone just reached us, so this is a good moment to deliver queued ticks.
                repository.flushPendingTicks()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't apply the Today snapshot", e)
            }
        }
    }

    @Deprecated("Kept for Wear OS versions that still deliver it")
    override fun onPeerConnected(peer: Node) {
        runBlocking {
            TodayRepository.getInstance(applicationContext).flushPendingTicks()
        }
    }

    private fun refreshSurfaces() {
        TileService.getUpdater(applicationContext).requestUpdate(TodayTileService::class.java)
        ComplicationDataSourceUpdateRequester.create(
            applicationContext,
            ComponentName(applicationContext, TodayComplicationService::class.java)
        ).requestUpdateAll()
    }

    companion object {
        private const val TAG = "WearDataListener"
        const val PATH_TODAY = "/today"
        const val KEY_MEALS_JSON = "meals_json"
    }
}
