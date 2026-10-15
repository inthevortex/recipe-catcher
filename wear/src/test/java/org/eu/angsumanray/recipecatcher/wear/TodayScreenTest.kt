package org.eu.angsumanray.recipecatcher.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TodayScreenTest {

    private lateinit var context: Context
    private lateinit var repository: TodayRepository
    private val day = LocalDate.of(2026, 10, 15)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = TodayRepository(context, today = { day }, sender = { true })
    }

    @Test
    fun `TodayScreen handles empty and populated meals states`() = runBlocking {
        // Empty state
        repository.updateFromSnapshot(WearTodaySnapshot(day.toString(), 0L, emptyList()))
        assertEquals(0, repository.meals().size)

        // Populated state with Wear Meal Items
        val items = listOf(
            WearMealItem(
                id = 101L,
                title = "Buttermilk Pancakes",
                kind = "cook",
                slot = "Breakfast",
                time = "08:30",
                done = false
            ),
            WearMealItem(
                id = 102L,
                title = "Caesar Salad",
                kind = "prep",
                slot = "Lunch",
                time = "13:00",
                done = true
            )
        )
        repository.updateFromSnapshot(WearTodaySnapshot(day.toString(), 1L, items))
        val current = repository.meals()
        assertEquals(2, current.size)
        assertEquals("Buttermilk Pancakes", current[0].title)
        assertFalse(current[0].done)
        assertEquals("Caesar Salad", current[1].title)
        assertTrue(current[1].done)
    }
}
