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

    @Test
    fun `calculateBalance does not inflate zero yield or negative portions`() {
        val zeroYield = PlanEntry(id = 1, slotId = 1, kind = "cook", portions = 0, portionsEatenNow = 1)
        val zeroBalance = PortionLedger.calculateBalance(zeroYield, emptyList())
        assertEquals(0, zeroBalance.initialPortions)
        assertEquals(0, zeroBalance.portionsEatenNow)
        assertEquals(0, zeroBalance.remainingPortions)

        val cook = PlanEntry(id = 2, slotId = 1, kind = "cook", portions = 4, portionsEatenNow = -3)
        val negativeLeftover = PlanEntry(id = 3, slotId = 2, kind = "leftover", portions = -2, fromEntryId = 2)
        val zeroLeftover = PlanEntry(id = 4, slotId = 3, kind = "leftover", portions = 0, fromEntryId = 2)
        val balance = PortionLedger.calculateBalance(cook, listOf(negativeLeftover, zeroLeftover))
        assertEquals(0, balance.portionsEatenNow)
        assertEquals(0, balance.portionsPlannedAsLeftover)
        assertEquals(4, balance.remainingPortions)
    }

    @Test
    fun `calculateBalance caps eaten now at portions made`() {
        val cook = PlanEntry(id = 1, slotId = 1, kind = "cook", portions = 2, portionsEatenNow = 5)
        val balance = PortionLedger.calculateBalance(cook, emptyList())
        assertEquals(2, balance.portionsEatenNow)
        assertEquals(0, balance.remainingPortions)
    }

    @Test
    fun `resolveCookSource follows a leftover of a leftover back to the cook entry`() {
        val cook = PlanEntry(id = 1, slotId = 1, kind = "cook", portions = 4)
        val leftover = PlanEntry(id = 2, slotId = 2, kind = "leftover", portions = 1, fromEntryId = 1)
        val leftoverOfLeftover = PlanEntry(id = 3, slotId = 3, kind = "leftover", portions = 1, fromEntryId = 2)
        val byId = listOf(cook, leftover, leftoverOfLeftover).associateBy { it.id }

        assertEquals(cook, PortionLedger.resolveCookSource(leftoverOfLeftover, byId))
        assertEquals(cook, PortionLedger.resolveCookSource(cook, byId))
    }

    @Test
    fun `resolveCookSource returns null for broken, looping or non-cook chains`() {
        val orphan = PlanEntry(id = 1, slotId = 1, kind = "leftover", portions = 1, fromEntryId = 99)
        val loopA = PlanEntry(id = 2, slotId = 1, kind = "leftover", portions = 1, fromEntryId = 3)
        val loopB = PlanEntry(id = 3, slotId = 1, kind = "leftover", portions = 1, fromEntryId = 2)
        val prep = PlanEntry(id = 4, slotId = 1, kind = "prep", text = "Oats")
        val fromPrep = PlanEntry(id = 5, slotId = 1, kind = "leftover", portions = 1, fromEntryId = 4)
        val byId = listOf(orphan, loopA, loopB, prep, fromPrep).associateBy { it.id }

        assertEquals(null, PortionLedger.resolveCookSource(orphan, byId))
        assertEquals(null, PortionLedger.resolveCookSource(loopA, byId))
        assertEquals(null, PortionLedger.resolveCookSource(fromPrep, byId))
    }

    @Test
    fun `availableLeftovers subtracts planned leftovers and drops fully used cooks`() {
        val slots = listOf(
            PlanSlot(id = 1, date = "2026-10-12", name = "Dinner"),
            PlanSlot(id = 2, date = "2026-10-13", name = "Lunch")
        )
        val cook = PlanEntry(id = 10, slotId = 1, kind = "cook", recipeId = 1, portions = 3, portionsEatenNow = 1)
        val leftover = PlanEntry(id = 11, slotId = 2, kind = "leftover", recipeId = 1, portions = 2, fromEntryId = 10)

        assertTrue(PortionLedger.availableLeftovers("2026-10-14", slots, listOf(cook, leftover)).isEmpty())
    }

    @Test
    fun `availableLeftovers charges a leftover of a leftover to the original cook`() {
        val slots = listOf(
            PlanSlot(id = 1, date = "2026-10-12", name = "Dinner"),
            PlanSlot(id = 2, date = "2026-10-13", name = "Lunch"),
            PlanSlot(id = 3, date = "2026-10-14", name = "Lunch")
        )
        val cook = PlanEntry(id = 10, slotId = 1, kind = "cook", recipeId = 1, portions = 4, portionsEatenNow = 1)
        val leftover = PlanEntry(id = 11, slotId = 2, kind = "leftover", recipeId = 1, portions = 1, fromEntryId = 10)
        val leftoverOfLeftover = PlanEntry(id = 12, slotId = 3, kind = "leftover", recipeId = 1, portions = 1, fromEntryId = 11)

        val available = PortionLedger.availableLeftovers("2026-10-15", slots, listOf(cook, leftover, leftoverOfLeftover))

        assertEquals(1, available.single().remainingPortions)
    }
}
