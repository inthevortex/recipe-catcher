package org.eu.angsumanray.recipecatcher.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.MainActivity
import org.eu.angsumanray.recipecatcher.data.repo.PlannerRepository

class ReminderReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReminderReceiverEntryPoint {
        fun plannerRepository(): PlannerRepository
        fun reminderScheduler(): ReminderScheduler
    }

    override fun onReceive(context: Context, intent: Intent) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReminderReceiverEntryPoint::class.java
        )

        when (intent.action) {
            ACTION_TRIGGER_REMINDER -> {
                val entryId = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
                val recipeId = intent.getLongExtra(EXTRA_RECIPE_ID, -1L)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Meal Reminder"
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Time to check your meal plan"

                showNotification(context, entryId, recipeId, title, message)
            }

            ACTION_MARK_DONE -> {
                val entryId = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                if (notificationId != -1) {
                    val notificationManager =
                        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(notificationId)
                }

                if (entryId != -1L) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            entryPoint.plannerRepository().toggleEntryDone(entryId)
                            entryPoint.reminderScheduler().rescheduleAll()
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        entryPoint.reminderScheduler().rescheduleAll()
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private fun showNotification(
        context: Context,
        entryId: Long,
        recipeId: Long,
        title: String,
        message: String
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Meal & Prep Reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications for recipe prep and start-cooking reminders"
        }
        notificationManager.createNotificationChannel(channel)

        // Open app on notification click
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (recipeId > 0L) {
                putExtra(MainActivity.EXTRA_RECIPE_ID, recipeId)
            }
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            entryId.toInt(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationId = entryId.toInt()

        // Done action
        val doneIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_MARK_DONE
            putExtra(EXTRA_ENTRY_ID, entryId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val donePendingIntent = PendingIntent.getBroadcast(
            context,
            entryId.toInt() + 10000,
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.checkbox_on_background, "Done", donePendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    companion object {
        const val CHANNEL_ID = "meal_reminders_channel"
        const val ACTION_TRIGGER_REMINDER = "org.eu.angsumanray.recipecatcher.ACTION_TRIGGER_REMINDER"
        const val ACTION_MARK_DONE = "org.eu.angsumanray.recipecatcher.ACTION_MARK_DONE"

        const val EXTRA_ENTRY_ID = "extra_entry_id"
        const val EXTRA_RECIPE_ID = "extra_recipe_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}
