package org.eu.angsumanray.recipecatcher.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.MainActivity
import org.eu.angsumanray.recipecatcher.core.planner.ReminderType
import org.eu.angsumanray.recipecatcher.data.repo.PlannerRepository
import org.eu.angsumanray.recipecatcher.widget.TodayWidget

/** Posts planned reminders, handles their Done action, and runs the midnight refresh. Not exported. */
class ReminderReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReminderReceiverEntryPoint {
        fun plannerRepository(): PlannerRepository
        fun reminderScheduler(): ReminderScheduler
        fun wearSync(): org.eu.angsumanray.recipecatcher.wear.WearSync
    }

    override fun onReceive(context: Context, intent: Intent) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReminderReceiverEntryPoint::class.java
        )

        when (intent.action) {
            ACTION_TRIGGER_REMINDER -> runAsync {
                val entryId = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
                // The alarm may predate a delete or a tick that happened while the app was closed.
                val entry = entryPoint.plannerRepository().getEntryById(entryId)
                if (entry != null && entry.doneAt == null) {
                    showNotification(context, intent)
                }
            }

            ACTION_MARK_DONE -> {
                val entryId = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                if (notificationId != -1) {
                    NotificationManagerCompat.from(context).cancel(notificationId)
                }
                if (entryId != -1L) {
                    runAsync {
                        entryPoint.plannerRepository().markEntryDone(entryId)
                        entryPoint.reminderScheduler().rescheduleAll()
                        TodayWidget.refresh(context)
                        entryPoint.wearSync().publishToday()
                    }
                }
            }

            ACTION_MIDNIGHT_REFRESH -> runAsync {
                entryPoint.reminderScheduler().rescheduleAll()
                TodayWidget.refresh(context)
                entryPoint.wearSync().publishToday()
            }
        }
    }

    private fun runAsync(block: suspend () -> Unit) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                block()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(context: Context, intent: Intent) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val entryId = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
        val recipeId = intent.getLongExtra(EXTRA_RECIPE_ID, -1L)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Meal reminder"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Time to check your meal plan"
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, entryId.toInt())
        val type = intent.getStringExtra(EXTRA_REMINDER_TYPE)
            ?.let { name -> ReminderType.entries.firstOrNull { it.name == name } }
            ?: ReminderType.START_COOKING

        NotificationChannels.ensure(context)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (recipeId > 0L) {
                putExtra(MainActivity.EXTRA_RECIPE_ID, recipeId)
            }
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val doneIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_MARK_DONE
            putExtra(EXTRA_ENTRY_ID, entryId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val donePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.channelFor(type))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.checkbox_on_background, "Done", donePendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    companion object {
        const val ACTION_TRIGGER_REMINDER = "org.eu.angsumanray.recipecatcher.ACTION_TRIGGER_REMINDER"
        const val ACTION_MARK_DONE = "org.eu.angsumanray.recipecatcher.ACTION_MARK_DONE"
        const val ACTION_MIDNIGHT_REFRESH = "org.eu.angsumanray.recipecatcher.ACTION_MIDNIGHT_REFRESH"

        const val EXTRA_ENTRY_ID = "extra_entry_id"
        const val EXTRA_RECIPE_ID = "extra_recipe_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_REMINDER_TYPE = "extra_reminder_type"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}
