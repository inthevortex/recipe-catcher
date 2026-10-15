package org.eu.angsumanray.recipecatcher.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.wear.complication.TodayComplicationService
import org.eu.angsumanray.recipecatcher.wear.data.TodayRepository
import org.eu.angsumanray.recipecatcher.wear.data.WearMealItem
import org.eu.angsumanray.recipecatcher.wear.data.WearTodaySnapshot
import org.eu.angsumanray.recipecatcher.wear.tile.TodayTileService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TodayRepositoryTest {

    private val day = LocalDate.of(2026, 10, 15)
    private lateinit var context: Context
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // The DataStore outlives a single test, so start every test from an empty queue.
        runBlocking {
            val reset = TodayRepository(context, today = { day }, sender = { true })
            reset.flushPendingTicks()
            reset.updateFromSnapshot(WearTodaySnapshot(day.toString(), 0L, emptyList()))
        }
    }

    private fun repository(sender: suspend (Long) -> Boolean = { false }) =
        TodayRepository(context, today = { day }, sender = sender)

    private fun snapshot(vararg items: WearMealItem, date: LocalDate = day) =
        WearTodaySnapshot(dateIso = date.toString(), timestamp = 1L, items = items.toList())

    @Test
    fun testWearTodaySnapshotSerialization() {
        val items = listOf(
            WearMealItem(id = 1L, title = "Pancakes", kind = "cook", slot = "Breakfast", time = "08:30", done = false),
            WearMealItem(id = 2L, title = "Salad", kind = "prep", slot = "Lunch", time = "12:30", done = true)
        )
        val snapshot = WearTodaySnapshot(dateIso = "2026-10-15", timestamp = 123456789L, items = items)

        val decoded = json.decodeFromString<WearTodaySnapshot>(json.encodeToString(WearTodaySnapshot.serializer(), snapshot))

        assertEquals("2026-10-15", decoded.dateIso)
        assertEquals(2, decoded.items.size)
        assertFalse(decoded.items[0].done)
        assertTrue(decoded.items[1].done)
    }

    @Test
    fun updateFromSnapshot_updatesMeals() = runBlocking {
        val repository = repository()
        repository.updateFromSnapshot(snapshot(WearMealItem(101L, "Curry", "cook", "Dinner")))

        val meals = repository.meals()
        assertEquals(listOf("Curry"), meals.map { it.title })
        assertFalse(meals[0].done)
    }

    @Test
    fun snapshotFromAnotherDay_showsNothing() = runBlocking {
        val repository = repository()
        repository.updateFromSnapshot(snapshot(WearMealItem(1L, "Old", "cook", "Dinner"), date = day.minusDays(1)))

        assertTrue(repository.meals().isEmpty())
    }

    @Test
    fun markDone_offline_isOptimisticQueuedAndSurvivesRestart() = runBlocking {
        val repository = repository(sender = { false })
        repository.updateFromSnapshot(snapshot(WearMealItem(202L, "Soup", "cook", "Lunch")))

        repository.markDone(202L)

        assertTrue(repository.meals().single().done)
        assertEquals(setOf(202L), repository.pendingDoneIds.value)

        val afterReboot = repository(sender = { false })
        assertTrue(afterReboot.meals().single().done)
        assertEquals(setOf(202L), afterReboot.pendingDoneIds.value)
    }

    @Test
    fun flushPendingTicks_deliversAndClearsQueue() = runBlocking {
        val offline = repository(sender = { false })
        offline.updateFromSnapshot(snapshot(WearMealItem(303L, "Rice", "cook", "Dinner")))
        offline.markDone(303L)

        val sent = CopyOnWriteArrayList<Long>()
        val online = repository(sender = { sent += it; true })
        online.flushPendingTicks()

        assertTrue(303L in sent)
        assertTrue(online.pendingDoneIds.value.isEmpty())
    }

    @Test
    fun snapshotConfirmingDone_dropsTickFromQueue() = runBlocking {
        val repository = repository(sender = { false })
        repository.updateFromSnapshot(snapshot(WearMealItem(404L, "Dal", "cook", "Dinner")))
        repository.markDone(404L)

        repository.updateFromSnapshot(snapshot(WearMealItem(404L, "Dal", "cook", "Dinner", done = true)))

        assertTrue(repository.pendingDoneIds.value.isEmpty())
        assertTrue(repository.meals().single().done)
    }

    @Test
    fun markDone_onDoneItem_doesNotQueue() = runBlocking {
        val repository = repository(sender = { false })
        repository.updateFromSnapshot(snapshot(WearMealItem(505L, "Tea", "cook", "Breakfast", done = true)))

        repository.markDone(505L)

        assertTrue(repository.pendingDoneIds.value.isEmpty())
    }

    @Test
    fun tile_tickIdsParseOnlyTickClicks() {
        assertEquals(42L, TodayTileService.tickedEntryId("tick:42"))
        assertNull(TodayTileService.tickedEntryId("open_today"))
        assertNull(TodayTileService.tickedEntryId("tick:abc"))
        assertNull(TodayTileService.tickedEntryId(null))
        assertNull(TodayTileService.tickedEntryId(""))
    }

    @Test
    fun complication_nextItemText_matchesSpecExample() {
        val prep = WearMealItem(1L, "oats", "prep", "Tomorrow", time = "21:00")
        assertEquals("Prep oats, 9:00 PM", TodayComplicationService.nextItemText(prep).replace("pm", "PM"))

        val untimed = WearMealItem(2L, "Pasta", "cook", "Dinner")
        assertEquals("Dinner: Pasta", TodayComplicationService.nextItemText(untimed))
    }
}
