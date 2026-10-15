package org.eu.angsumanray.recipecatcher.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.wear.data.TodayRepository
import org.eu.angsumanray.recipecatcher.wear.data.WearMealItem
import org.eu.angsumanray.recipecatcher.wear.data.WearTodaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class TodayRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: TodayRepository
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = TodayRepository(context)
    }

    @Test
    fun testWearTodaySnapshotSerialization() {
        val items = listOf(
            WearMealItem(id = 1L, title = "Pancakes", kind = "cook", slot = "Breakfast", time = "08:30", done = false),
            WearMealItem(id = 2L, title = "Salad", kind = "prep", slot = "Lunch", time = "12:30", done = true)
        )
        val snapshot = WearTodaySnapshot(dateIso = "2026-10-15", timestamp = 123456789L, items = items)

        val encoded = json.encodeToString(WearTodaySnapshot.serializer(), snapshot)
        val decoded = json.decodeFromString<WearTodaySnapshot>(encoded)

        assertEquals("2026-10-15", decoded.dateIso)
        assertEquals(2, decoded.items.size)
        assertEquals("Pancakes", decoded.items[0].title)
        assertFalse(decoded.items[0].done)
        assertTrue(decoded.items[1].done)
    }

    @Test
    fun testUpdateFromSnapshotUpdatesMealsFlow() = runTest {
        val items = listOf(
            WearMealItem(id = 101L, title = "Curry", kind = "cook", slot = "Dinner", done = false)
        )
        val snapshot = WearTodaySnapshot(dateIso = "2026-10-15", timestamp = 123456789L, items = items)

        repository.updateFromSnapshot(snapshot)

        val currentMeals = repository.getMeals()
        assertEquals(1, currentMeals.size)
        assertEquals("Curry", currentMeals[0].title)
        assertFalse(currentMeals[0].done)
    }

    @Test
    fun testToggleMealOptimisticallyUpdatesState() = runTest {
        val items = listOf(
            WearMealItem(id = 202L, title = "Soup", kind = "cook", slot = "Lunch", done = false)
        )
        val snapshot = WearTodaySnapshot(dateIso = "2026-10-15", timestamp = 123456789L, items = items)
        repository.updateFromSnapshot(snapshot)

        repository.toggleMeal(202L)

        val currentMeals = repository.getMeals()
        assertEquals(1, currentMeals.size)
        assertTrue("Meal should be marked done optimistically", currentMeals[0].done)
    }
}
