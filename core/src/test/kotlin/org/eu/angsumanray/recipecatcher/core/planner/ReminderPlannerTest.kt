package org.eu.angsumanray.recipecatcher.core.planner

import org.eu.angsumanray.recipecatcher.core.model.PlanEntry
import org.eu.angsumanray.recipecatcher.core.model.PlanSlot
import org.eu.angsumanray.recipecatcher.core.model.Recipe
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReminderPlannerTest {

    private val zoneId = ZoneId.of("UTC")

    private fun sampleRecipe(id: Long, title: String, totalMin: Int): Recipe {
        return Recipe(
            id = id,
            sourceType = SourceType.MANUAL,
            sourceKey = "r-$id",
            title = title,
            totalMin = totalMin
        )
    }

    @Test
    fun `computeReminders generates prep alert for night before`() {
        val slot = PlanSlot(id = 1, date = "2026-10-15", name = "Breakfast")
        val prepEntry = PlanEntry(
            id = 10,
            slotId = 1,
            kind = "prep",
            text = "Overnight oats",
            prepNightBefore = true
        )

        val reminders = ReminderPlanner.computeReminders(
            slots = listOf(slot),
            entries = listOf(prepEntry),
            recipes = emptyMap(),
            prepAlertEnabled = true,
            prepAlertTime = "21:00",
            zoneId = zoneId
        )

        assertEquals(1, reminders.size)
        val reminder = reminders.first()
        assertEquals(ReminderType.PREP_NIGHT_BEFORE, reminder.type)
        assertEquals(10L, reminder.entryId)

        // Expected time: 2026-10-14 21:00 UTC
        val expected = LocalDateTime.of(LocalDate.parse("2026-10-14"), LocalTime.of(21, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, reminder.triggerEpochMillis)
    }

    @Test
    fun `computeReminders generates start cooking alert before slot time`() {
        val slot = PlanSlot(id = 2, date = "2026-10-15", name = "Dinner", time = "19:30")
        val recipe = sampleRecipe(id = 100, title = "Lasagna", totalMin = 60)
        val cookEntry = PlanEntry(
            id = 20,
            slotId = 2,
            kind = "cook",
            recipeId = 100
        )

        val reminders = ReminderPlanner.computeReminders(
            slots = listOf(slot),
            entries = listOf(cookEntry),
            recipes = mapOf(100L to recipe),
            cookingAlertEnabled = true,
            zoneId = zoneId
        )

        assertEquals(1, reminders.size)
        val reminder = reminders.first()
        assertEquals(ReminderType.START_COOKING, reminder.type)

        // 19:30 minus 60 min -> 18:30
        val expected = LocalDateTime.of(LocalDate.parse("2026-10-15"), LocalTime.of(18, 30))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, reminder.triggerEpochMillis)
    }

    @Test
    fun `computeReminders defaults to 30 min if recipe total time unknown`() {
        val slot = PlanSlot(id = 3, date = "2026-10-15", name = "Lunch", time = "13:00")
        val cookEntry = PlanEntry(
            id = 30,
            slotId = 3,
            kind = "cook",
            text = "Custom Pasta"
        )

        val reminders = ReminderPlanner.computeReminders(
            slots = listOf(slot),
            entries = listOf(cookEntry),
            recipes = emptyMap(),
            cookingAlertEnabled = true,
            zoneId = zoneId
        )

        assertEquals(1, reminders.size)
        // 13:00 minus 30 min -> 12:30
        val expected = LocalDateTime.of(LocalDate.parse("2026-10-15"), LocalTime.of(12, 30))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, reminders.first().triggerEpochMillis)
    }

    @Test
    fun `computeReminders respects alert toggles and skips done entries`() {
        val slot = PlanSlot(id = 1, date = "2026-10-15", name = "Dinner", time = "20:00")
        val cookEntry = PlanEntry(id = 1, slotId = 1, kind = "cook", doneAt = 123456789L)
        val prepEntry = PlanEntry(id = 2, slotId = 1, kind = "prep", prepNightBefore = true)

        val remindersNone = ReminderPlanner.computeReminders(
            slots = listOf(slot),
            entries = listOf(cookEntry, prepEntry),
            recipes = emptyMap(),
            prepAlertEnabled = false,
            cookingAlertEnabled = false,
            zoneId = zoneId
        )
        assertTrue(remindersNone.isEmpty())
    }

    @Test
    fun `computeReminders sends night-before alerts only for prep entries`() {
        val slot = PlanSlot(id = 1, date = "2026-10-15", name = "Dinner")
        val cookFlagged = PlanEntry(id = 10, slotId = 1, kind = "cook", recipeId = 1, prepNightBefore = true)

        val reminders = ReminderPlanner.computeReminders(
            slots = listOf(slot),
            entries = listOf(cookFlagged),
            recipes = emptyMap(),
            zoneId = zoneId
        )

        assertTrue(reminders.none { it.type == ReminderType.PREP_NIGHT_BEFORE })
    }

    @Test
    fun `computeReminders falls back to prep plus cook minutes when total is missing`() {
        val slot = PlanSlot(id = 1, date = "2026-10-15", name = "Dinner", time = "20:00")
        val entry = PlanEntry(id = 10, slotId = 1, kind = "cook", recipeId = 1)
        val recipe = Recipe(
            id = 1,
            sourceType = SourceType.MANUAL,
            sourceKey = "r-1",
            title = "Dal",
            prepMin = 15,
            cookMin = 45
        )

        val reminders = ReminderPlanner.computeReminders(
            slots = listOf(slot),
            entries = listOf(entry),
            recipes = mapOf(1L to recipe),
            zoneId = zoneId
        )

        val expected = LocalDateTime.of(LocalDate.parse("2026-10-15"), LocalTime.of(19, 0))
            .atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(expected, reminders.single().triggerEpochMillis)
    }
}
