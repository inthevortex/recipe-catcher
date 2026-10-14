package org.eu.angsumanray.recipecatcher.core.planner

import org.eu.angsumanray.recipecatcher.core.model.PlanEntry
import org.eu.angsumanray.recipecatcher.core.model.PlanEntryKind
import org.eu.angsumanray.recipecatcher.core.model.PlanSlot

/**
 * Detailed balance of portions for a single Cook entry.
 */
data class PortionBalance(
    val cookEntryId: Long,
    val recipeId: Long?,
    val initialPortions: Int,
    val portionsEatenNow: Int,
    val portionsPlannedAsLeftover: Int,
    val remainingPortions: Int
)

/**
 * Represents a cook entry that has remaining leftover portions available to be planned on a given date.
 */
data class AvailableLeftover(
    val cookEntryId: Long,
    val recipeId: Long?,
    val slotId: Long,
    val slotDate: String, // "YYYY-MM-DD"
    val slotName: String,
    val initialPortions: Int,
    val remainingPortions: Int
)

/**
 * Core domain ledger for managing portion counts, leftovers availability, and entry dependencies.
 * Pure Kotlin JVM with zero Android dependencies.
 */
object PortionLedger {

    /**
     * Determines default portions from recipe yield/servings.
     */
    fun calculateDefaultPortions(recipeServings: Int?): Int {
        return (recipeServings ?: 1).coerceAtLeast(1)
    }

    /**
     * Calculates the portion balance for a specific [cookEntry] based on any [dependentLeftovers]
     * that reference it via `fromEntryId == cookEntry.id`.
     */
    fun calculateBalance(
        cookEntry: PlanEntry,
        dependentLeftovers: List<PlanEntry>
    ): PortionBalance {
        val initial = cookEntry.portions.coerceAtLeast(1)
        val eatenNow = cookEntry.portionsEatenNow.coerceAtLeast(0)
        val leftoverPlanned = dependentLeftovers
            .filter { it.fromEntryId == cookEntry.id && it.kind == PlanEntryKind.LEFTOVER.value }
            .sumOf { it.portions.coerceAtLeast(1) }

        val remaining = (initial - eatenNow - leftoverPlanned).coerceAtLeast(0)

        return PortionBalance(
            cookEntryId = cookEntry.id,
            recipeId = cookEntry.recipeId,
            initialPortions = initial,
            portionsEatenNow = eatenNow,
            portionsPlannedAsLeftover = leftoverPlanned,
            remainingPortions = remaining
        )
    }

    /**
     * Finds all leftover entries that depend on [cookEntryId].
     */
    fun findDependentLeftovers(
        cookEntryId: Long,
        allEntries: List<PlanEntry>
    ): List<PlanEntry> {
        return allEntries.filter {
            it.fromEntryId == cookEntryId && it.kind == PlanEntryKind.LEFTOVER.value
        }
    }

    /**
     * Computes the available leftovers on [targetDate].
     * Only cook entries occurring on or before [targetDate] that still have portions remaining
     * are returned.
     *
     * @param targetDate ISO-8601 date string ("YYYY-MM-DD").
     * @param allSlots List of all slots.
     * @param allEntries List of all plan entries.
     */
    fun availableLeftovers(
        targetDate: String,
        allSlots: List<PlanSlot>,
        allEntries: List<PlanEntry>
    ): List<AvailableLeftover> {
        val slotMap = allSlots.associateBy { it.id }
        val cookEntries = allEntries.filter { it.kind == PlanEntryKind.COOK.value }

        return cookEntries.mapNotNull { cook ->
            val slot = slotMap[cook.slotId] ?: return@mapNotNull null
            // Must have occurred on or before the target date
            if (slot.date > targetDate) return@mapNotNull null

            val dependents = allEntries.filter { it.fromEntryId == cook.id }
            val balance = calculateBalance(cook, dependents)

            if (balance.remainingPortions > 0) {
                AvailableLeftover(
                    cookEntryId = cook.id,
                    recipeId = cook.recipeId,
                    slotId = slot.id,
                    slotDate = slot.date,
                    slotName = slot.name,
                    initialPortions = balance.initialPortions,
                    remainingPortions = balance.remainingPortions
                )
            } else {
                null
            }
        }.sortedBy { it.slotDate }
    }
}
