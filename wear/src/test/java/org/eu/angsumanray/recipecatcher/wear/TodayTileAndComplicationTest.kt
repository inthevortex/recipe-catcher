package org.eu.angsumanray.recipecatcher.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.tiles.RequestBuilders
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.eu.angsumanray.recipecatcher.wear.complication.TodayComplicationService
import org.eu.angsumanray.recipecatcher.wear.data.TodayRepository
import org.eu.angsumanray.recipecatcher.wear.data.WearMealItem
import org.eu.angsumanray.recipecatcher.wear.data.WearTodaySnapshot
import org.eu.angsumanray.recipecatcher.wear.tile.TodayTileService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class TodayTileAndComplicationTest {

    private lateinit var context: Context
    private lateinit var repository: TodayRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = TodayRepository.getInstance(context)
    }

    @Test
    fun testTileRequestWithRemainingMeals() = runTest {
        val items = listOf(
            WearMealItem(id = 1L, title = "Oatmeal", kind = "cook", slot = "Breakfast", done = false),
            WearMealItem(id = 2L, title = "Sandwich", kind = "prep", slot = "Lunch", done = true),
            WearMealItem(id = 3L, title = "Curry", kind = "cook", slot = "Dinner", done = false)
        )
        repository.updateFromSnapshot(WearTodaySnapshot("2026-10-15", 1000L, items))

        val tileService = object : TodayTileService() {
            init {
                attachBaseContext(context)
            }
        }
        val deviceConfig = DeviceParameters.Builder()
            .setScreenWidthDp(200)
            .setScreenHeightDp(200)
            .setScreenDensity(2.0f)
            .build()
        val request = RequestBuilders.TileRequest.Builder()
            .setDeviceConfiguration(deviceConfig)
            .build()

        val tileFuture = tileService.onTileRequest(request)
        val tile = tileFuture.get()
        assertNotNull(tile)
        assertNotNull(tile.tileTimeline)
    }

    @Test
    fun testComplicationRequestReturnsCorrectData() = runTest {
        val items = listOf(
            WearMealItem(id = 10L, title = "Pasta", kind = "cook", slot = "Dinner", done = false),
            WearMealItem(id = 11L, title = "Toast", kind = "cook", slot = "Breakfast", done = true)
        )
        repository.updateFromSnapshot(WearTodaySnapshot("2026-10-15", 2000L, items))

        val complicationService = object : TodayComplicationService() {
            init {
                attachBaseContext(context)
            }
        }

        // Short text: 1 left
        val now = java.time.Instant.now()
        val shortRequest = ComplicationRequest(1, ComplicationType.SHORT_TEXT, false)
        val shortData = complicationService.onComplicationRequest(shortRequest) as? ShortTextComplicationData
        assertNotNull(shortData)
        assertEquals("1 left", shortData?.text?.getTextAt(context.resources, now)?.toString())

        // Long text: Dinner: Pasta
        val longRequest = ComplicationRequest(2, ComplicationType.LONG_TEXT, false)
        val longData = complicationService.onComplicationRequest(longRequest) as? LongTextComplicationData
        assertNotNull(longData)
        assertTrue(longData?.text?.getTextAt(context.resources, now)?.toString()?.contains("Pasta") == true)

        // Ranged value: 1/2 done
        val rangedRequest = ComplicationRequest(3, ComplicationType.RANGED_VALUE, false)
        val rangedData = complicationService.onComplicationRequest(rangedRequest) as? RangedValueComplicationData
        assertNotNull(rangedData)
        assertEquals(1f, rangedData?.value)
        assertEquals(2f, rangedData?.max)
    }

    @Test
    fun testComplicationPreviewData() {
        val complicationService = object : TodayComplicationService() {
            init {
                attachBaseContext(context)
            }
        }
        assertNotNull(complicationService.getPreviewData(ComplicationType.SHORT_TEXT))
        assertNotNull(complicationService.getPreviewData(ComplicationType.LONG_TEXT))
        assertNotNull(complicationService.getPreviewData(ComplicationType.RANGED_VALUE))
    }
}
