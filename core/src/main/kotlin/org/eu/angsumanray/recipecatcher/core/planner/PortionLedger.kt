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
        val initial = cookEntry.portions.coerceAtLeast(0)
        val eatenNow = cookEntry.portionsEatenNow.coerceIn(0, initial)
        val leftoverPlanned = dependentLeftovers
            .filter { it.fromEntryId == cookEntry.id && it.kind == PlanEntryKind.LEFTOVER.value }
            .sumOf { it.portions.coerceAtLeast(0) }

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
     * Follows `fromEntryId` links from [entry] back to the Cook entry that produced the food, so a
     * leftover planned from another leftover draws on the original cook. Returns null when the chain
     * is broken, loops, or ends on an entry that is not a Cook entry.
     */
    fun resolveCookSource(entry: PlanEntry, entriesById: Map<Long, PlanEntry>): PlanEntry? {
        var current = entry
        val visited = mutableSetOf<Long>()
        while (current.kind != PlanEntryKind.COOK.value) {
            if (!visited.add(current.id)) return null
            val parentId = current.fromEntryId ?: return null
            current = entriesById[parentId] ?: return null
        }
        return current
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
        val entriesById = allEntries.associateBy { it.id }
        val cookEntries = allEntries.filter { it.kind == PlanEntryKind.COOK.value }

        // Charge every leftover to the cook entry at the root of its chain.
        val leftoversByCook = allEntries
            .filter { it.kind == PlanEntryKind.LEFTOVER.value }
            .mapNotNull { leftover ->
                resolveCookSource(leftover, entriesById)?.let { cook -> leftover.copy(fromEntryId = cook.id) }
            }
            .groupBy { it.fromEntryId }

        return cookEntries.mapNotNull { cook ->
            val slot = slotMap[cook.slotId] ?: return@mapNotNull null
            // Must have occurred on or before the target date
            if (slot.date > targetDate) return@mapNotNull null

            val balance = calculateBalance(cook, leftoversByCook[cook.id].orEmpty())

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
