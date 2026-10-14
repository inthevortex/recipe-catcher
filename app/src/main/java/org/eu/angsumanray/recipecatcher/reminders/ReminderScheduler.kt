package org.eu.angsumanray.recipecatcher.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eu.angsumanray.recipecatcher.core.model.PlanEntry
import org.eu.angsumanray.recipecatcher.core.model.PlanSlot
import org.eu.angsumanray.recipecatcher.core.model.Recipe
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.core.planner.PlannedReminder
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

    // Request codes of the alarms set last time, so ones whose entry was deleted, ticked or
    // switched off in Settings get cancelled instead of firing anyway.
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun rescheduleAll() = mutex.withLock {
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
                prepMin = r.prepMin,
                cookMin = r.cookMin,
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

        cancelPrevious()

        val scheduled = mutableSetOf<String>()
        for (reminder in reminders) {
            val requestCode = requestCodeFor(reminder)
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                reminder.triggerEpochMillis,
                WINDOW_MILLIS,
                reminderPendingIntent(requestCode, reminder)
            )
            scheduled += requestCode.toString()
        }
        prefs.edit().putStringSet(KEY_SCHEDULED, scheduled).apply()

        scheduleMidnightRefresh()
    }

    private fun cancelPrevious() {
        val previous = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        for (code in previous.mapNotNull { it.toIntOrNull() }) {
            PendingIntent.getBroadcast(
                context,
                code,
                Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_TRIGGER_REMINDER),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }
    }

    private fun reminderPendingIntent(requestCode: Int, reminder: PlannedReminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TRIGGER_REMINDER
            putExtra(ReminderReceiver.EXTRA_ENTRY_ID, reminder.entryId)
            putExtra(ReminderReceiver.EXTRA_RECIPE_ID, reminder.recipeId ?: -1L)
            putExtra(ReminderReceiver.EXTRA_TITLE, reminder.title)
            putExtra(ReminderReceiver.EXTRA_MESSAGE, reminder.message)
            putExtra(ReminderReceiver.EXTRA_REMINDER_TYPE, reminder.type.name)
            putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, requestCode)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Wakes the app just after midnight so Today, the widget and the next day's alarms roll over. */
    private fun scheduleMidnightRefresh() {
        val zone = ZoneId.systemDefault()
        val trigger = LocalDate.now().plusDays(1).atStartOfDay(zone).plusMinutes(1).toInstant().toEpochMilli()
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_MIDNIGHT_REFRESH)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            MIDNIGHT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setWindow(AlarmManager.RTC, trigger, MIDNIGHT_WINDOW_MILLIS, pendingIntent)
    }

    private companion object {
        const val PREFS_NAME = "reminder_alarms"
        const val KEY_SCHEDULED = "scheduled_request_codes"
        const val MIDNIGHT_REQUEST_CODE = 0

        // A window of about 10 minutes lets the app avoid the exact-alarm permission.
        const val WINDOW_MILLIS = 10 * 60 * 1000L
        const val MIDNIGHT_WINDOW_MILLIS = 5 * 60 * 1000L

        // One entry can carry both a prep and a cook alert, so each type gets its own code.
        fun requestCodeFor(reminder: PlannedReminder): Int =
            (reminder.entryId * 2 + reminder.type.ordinal).toInt()
    }
}
