package org.eu.angsumanray.recipecatcher.core.planner

import org.eu.angsumanray.recipecatcher.core.model.PlanEntry
import org.eu.angsumanray.recipecatcher.core.model.PlanEntryKind
import org.eu.angsumanray.recipecatcher.core.model.PlanSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortionLedgerTest {

    @Test
    fun `calculateBalance calculates remaining portions correctly`() {
        val cookEntry = PlanEntry(
            id = 10L,
            slotId = 1L,
            kind = PlanEntryKind.COOK.value,
            recipeId = 100L,
            portions = 4,
            portionsEatenNow = 1
        )

        val leftover1 = PlanEntry(
            id = 11L,
            slotId = 2L,
            kind = PlanEntryKind.LEFTOVER.value,
            portions = 1,
            fromEntryId = 10L
        )

        val leftover2 = PlanEntry(
            id = 12L,
            slotId = 3L,
            kind = PlanEntryKind.LEFTOVER.value,
            portions = 1,
            fromEntryId = 10L
        )

        val balance = PortionLedger.calculateBalance(cookEntry, listOf(leftover1, leftover2))

        assertEquals(4, balance.initialPortions)
        assertEquals(1, balance.portionsEatenNow)
        assertEquals(2, balance.portionsPlannedAsLeftover)
        assertEquals(1, balance.remainingPortions)
    }

    @Test
    fun `calculateBalance clamps remaining portions to zero if over-consumed`() {
        val cookEntry = PlanEntry(
            id = 10L,
            slotId = 1L,
            kind = PlanEntryKind.COOK.value,
            recipeId = 100L,
            portions = 2,
            portionsEatenNow = 2
        )

        val leftover = PlanEntry(
            id = 11L,
            slotId = 2L,
            kind = PlanEntryKind.LEFTOVER.value,
            portions = 2,
            fromEntryId = 10L
        )

        val balance = PortionLedger.calculateBalance(cookEntry, listOf(leftover))
        assertEquals(0, balance.remainingPortions)
    }

    @Test
    fun `findDependentLeftovers returns entries referencing cook entry`() {
        val cookId = 5L
        val entries = listOf(
            PlanEntry(id = 1, slotId = 1, kind = "cook", portions = 4),
            PlanEntry(id = 2, slotId = 2, kind = "leftover", fromEntryId = cookId, portions = 1),
            PlanEntry(id = 3, slotId = 3, kind = "prep", text = "Oats"),
            PlanEntry(id = 4, slotId = 4, kind = "leftover", fromEntryId = cookId, portions = 2),
            PlanEntry(id = 6, slotId = 5, kind = "leftover", fromEntryId = 99L, portions = 1)
        )

        val dependents = PortionLedger.findDependentLeftovers(cookId, entries)
        assertEquals(2, dependents.size)
        assertEquals(listOf(2L, 4L), dependents.map { it.id })
    }

    @Test
    fun `availableLeftovers returns only past or present entries with remaining portions`() {
        val slots = listOf(
            PlanSlot(id = 1, date = "2026-10-12", name = "Monday Dinner"),
            PlanSlot(id = 2, date = "2026-10-13", name = "Tuesday Dinner"),
            PlanSlot(id = 3, date = "2026-10-15", name = "Thursday Dinner")
        )

        val cook1 = PlanEntry(id = 10, slotId = 1, kind = "cook", recipeId = 101, portions = 4, portionsEatenNow = 1)
        val cook2 = PlanEntry(id = 20, slotId = 2, kind = "cook", recipeId = 102, portions = 2, portionsEatenNow = 2)
        val cook3 = PlanEntry(id = 30, slotId = 3, kind = "cook", recipeId = 103, portions = 4, portionsEatenNow = 1)

        val allEntries = listOf(cook1, cook2, cook3)

        // On Tuesday 2026-10-13:
        // cook1 has 3 portions remaining (from Mon 10-12) -> available
        // cook2 has 0 portions remaining (2 initial - 2 eaten) -> not available
        // cook3 is on Thu 10-15 -> not yet available
        val available = PortionLedger.availableLeftovers("2026-10-13", slots, allEntries)

        assertEquals(1, available.size)
        val item = available.first()
        assertEquals(10L, item.cookEntryId)
        assertEquals("2026-10-12", item.slotDate)
        assertEquals(3, item.remainingPortions)
    }

    @Test
    fun `defaultPortions falls back to recipe servings or 1`() {
        assertEquals(4, PortionLedger.calculateDefaultPortions(4))
        assertEquals(1, PortionLedger.calculateDefaultPortions(null))
        assertEquals(1, PortionLedger.calculateDefaultPortions(0))
    }
}
