package org.eu.angsumanray.recipecatcher.wear

import android.content.ComponentName
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.WearableListenerService
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.wear.complication.TodayComplicationService
import org.eu.angsumanray.recipecatcher.wear.data.TodayRepository
import org.eu.angsumanray.recipecatcher.wear.data.WearTodaySnapshot
import org.eu.angsumanray.recipecatcher.wear.tile.TodayTileService

class WearDataListenerService : WearableListenerService() {

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == PATH_TODAY) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val jsonStr = dataMap.getString(KEY_MEALS_JSON) ?: continue
                scope.launch {
                    try {
                        val snapshot = json.decodeFromString<WearTodaySnapshot>(jsonStr)
                        TodayRepository.getInstance(applicationContext).updateFromSnapshot(snapshot)

                        // Request tile and complication updates
                        TileService.getUpdater(applicationContext).requestUpdate(TodayTileService::class.java)
                        ComplicationDataSourceUpdateRequester.create(
                            applicationContext,
                            ComponentName(applicationContext, TodayComplicationService::class.java)
                        ).requestUpdateAll()
                    } catch (e: Exception) {
                        // Ignore parse or update exceptions
                    }
                }
            }
        }
    }

    override fun onPeerConnected(peer: Node) {
        scope.launch {
            TodayRepository.getInstance(applicationContext).flushPendingTicks()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val PATH_TODAY = "/today"
        const val KEY_MEALS_JSON = "meals_json"
    }
}
