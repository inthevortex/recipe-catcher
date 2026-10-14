package org.eu.angsumanray.recipecatcher.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.eu.angsumanray.recipecatcher.core.model.PlanEntry
import org.eu.angsumanray.recipecatcher.core.model.PlanSlot
import org.eu.angsumanray.recipecatcher.core.model.Recipe
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.core.planner.ReminderPlanner
import org.eu.angsumanray.recipecatcher.data.repo.PlannerRepository
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val plannerRepository: PlannerRepository,
    private val settingsStore: SettingsStore
) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    suspend fun rescheduleAll() {
        val prepEnabled = settingsStore.prepAlertEnabledFlow.first()
        val prepTime = settingsStore.prepAlertTimeFlow.first()
        val cookingEnabled = settingsStore.cookingAlertsEnabledFlow.first()

        val today = LocalDate.now()
        val nextWeek = today.plusDays(7)
        val slots = plannerRepository.getSlotsBetween(today, nextWeek)
        val entries = plannerRepository.getEntriesBetween(today, nextWeek)

        val coreSlots = slots.map {
            PlanSlot(
                id = it.id,
                date = it.date.toString(),
                name = it.name,
                time = it.time?.toString(),
                position = it.position
            )
        }

        val coreEntries = entries.map {
            PlanEntry(
                id = it.id,
                slotId = it.slotId,
                kind = it.kind,
                recipeId = it.recipeId,
                text = it.text,
                portions = it.portions,
                portionsEatenNow = it.portionsEatenNow,
                fromEntryId = it.fromEntryId,
                prepNightBefore = it.prepNightBefore,
                doneAt = it.doneAt,
                position = it.position
            )
        }

        val recipeIds = entries.mapNotNull { it.recipeId }.distinct()
        val recipesMap = mutableMapOf<Long, Recipe>()
        for (id in recipeIds) {
            val r = plannerRepository.getRecipe(id) ?: continue
            recipesMap[id] = Recipe(
                id = r.id,
                sourceType = SourceType.from(r.sourceType),
                sourceKey = r.sourceKey,
                title = r.title,
                totalMin = r.totalMin
            )
        }

        val reminders = ReminderPlanner.computeReminders(
            slots = coreSlots,
            entries = coreEntries,
            recipes = recipesMap,
            prepAlertEnabled = prepEnabled,
            prepAlertTime = prepTime,
            cookingAlertEnabled = cookingEnabled,
            nowEpochMillis = System.currentTimeMillis(),
            zoneId = ZoneId.systemDefault()
        )

        for (reminder in reminders) {
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_TRIGGER_REMINDER
                putExtra(ReminderReceiver.EXTRA_ENTRY_ID, reminder.entryId)
                putExtra(ReminderReceiver.EXTRA_RECIPE_ID, reminder.recipeId ?: -1L)
                putExtra(ReminderReceiver.EXTRA_TITLE, reminder.title)
                putExtra(ReminderReceiver.EXTRA_MESSAGE, reminder.message)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                reminder.entryId.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 10-minute window avoids exact alarm permissions
            val windowMillis = 10 * 60 * 1000L
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                reminder.triggerEpochMillis,
                windowMillis,
                pendingIntent
            )
        }
    }
}
