package org.eu.angsumanray.recipecatcher.core.planner

import org.eu.angsumanray.recipecatcher.core.model.PlanEntry
import org.eu.angsumanray.recipecatcher.core.model.PlanEntryKind
import org.eu.angsumanray.recipecatcher.core.model.PlanSlot
import org.eu.angsumanray.recipecatcher.core.model.Recipe
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ReminderType {
    PREP_NIGHT_BEFORE,
    START_COOKING
}

data class PlannedReminder(
    val entryId: Long,
    val slotId: Long,
    val recipeId: Long?,
    val title: String,
    val message: String,
    val triggerEpochMillis: Long,
    val type: ReminderType
)

/**
 * Computes notification trigger times for prep and start-cooking reminders.
 * Pure Kotlin JVM with zero Android dependencies.
 */
object ReminderPlanner {

    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Calculates all scheduled reminders for the given [entries] and [slots].
     *
     * @param slots All plan slots.
     * @param entries All plan entries.
     * @param recipes Map of recipe ID to Recipe.
     * @param prepAlertEnabled Whether night-before prep reminders are enabled.
     * @param prepAlertTime Night-before prep reminder time string ("HH:mm", default "21:00").
     * @param cookingAlertEnabled Whether start-cooking reminders are enabled.
     * @param nowEpochMillis Current epoch milliseconds for filtering past reminders (null to include all).
     * @param zoneId Timezone used for epoch calculations.
     */
    fun computeReminders(
        slots: List<PlanSlot>,
        entries: List<PlanEntry>,
        recipes: Map<Long, Recipe>,
        prepAlertEnabled: Boolean = true,
        prepAlertTime: String = "21:00",
        cookingAlertEnabled: Boolean = true,
        nowEpochMillis: Long? = null,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<PlannedReminder> {
        val slotMap = slots.associateBy { it.id }
        val reminders = mutableListOf<PlannedReminder>()

        val parsedPrepTime = try {
            LocalTime.parse(prepAlertTime.trim(), TIME_FORMATTER)
        } catch (_: Exception) {
            LocalTime.of(21, 0)
        }

        for (entry in entries) {
            // If entry is already marked done, skip alert
            if (entry.doneAt != null) continue

            val slot = slotMap[entry.slotId] ?: continue
            val slotDate = try {
                LocalDate.parse(slot.date)
            } catch (_: Exception) {
                continue
            }

            val recipe = entry.recipeId?.let { recipes[it] }
            val itemTitle = recipe?.title ?: entry.text ?: "Meal"

            // 1. Night-before prep alert
            if (prepAlertEnabled && entry.prepNightBefore && entry.kind == PlanEntryKind.PREP.value) {
                val alertDate = slotDate.minusDays(1)
                val alertDateTime = LocalDateTime.of(alertDate, parsedPrepTime)
                val triggerMillis = alertDateTime.atZone(zoneId).toInstant().toEpochMilli()

                if (nowEpochMillis == null || triggerMillis > nowEpochMillis) {
                    reminders.add(
                        PlannedReminder(
                            entryId = entry.id,
                            slotId = slot.id,
                            recipeId = entry.recipeId,
                            title = "Prep: $itemTitle",
                            message = "Prep for tomorrow's ${slot.name}",
                            triggerEpochMillis = triggerMillis,
                            type = ReminderType.PREP_NIGHT_BEFORE
                        )
                    )
                }
            }

            // 2. Start-cooking alert
            if (cookingAlertEnabled && entry.kind == PlanEntryKind.COOK.value && !slot.time.isNullOrBlank()) {
                val slotTime = try {
                    LocalTime.parse(slot.time.trim(), TIME_FORMATTER)
                } catch (_: Exception) {
                    null
                }

                if (slotTime != null) {
                    val mealDateTime = LocalDateTime.of(slotDate, slotTime)
                    val totalMinutes = recipe?.let(::recipeMinutes) ?: DEFAULT_COOK_MINUTES
                    val alertDateTime = mealDateTime.minusMinutes(totalMinutes.toLong())
                    val triggerMillis = alertDateTime.atZone(zoneId).toInstant().toEpochMilli()

                    if (nowEpochMillis == null || triggerMillis > nowEpochMillis) {
                        reminders.add(
                            PlannedReminder(
                                entryId = entry.id,
                                slotId = slot.id,
                                recipeId = entry.recipeId,
                                title = "Time to cook: $itemTitle",
                                message = "Takes ~$totalMinutes min for ${slot.name}",
                                triggerEpochMillis = triggerMillis,
                                type = ReminderType.START_COOKING
                            )
                        )
                    }
                }
            }
        }

        return reminders.sortedBy { it.triggerEpochMillis }
    }

    private const val DEFAULT_COOK_MINUTES = 30

    /** The recipe's total time, falling back to prep + cook when only those are known. */
    private fun recipeMinutes(recipe: Recipe): Int? {
        recipe.totalMin?.takeIf { it > 0 }?.let { return it }
        val parts = (recipe.prepMin ?: 0).coerceAtLeast(0) + (recipe.cookMin ?: 0).coerceAtLeast(0)
        return parts.takeIf { it > 0 }
    }
}
