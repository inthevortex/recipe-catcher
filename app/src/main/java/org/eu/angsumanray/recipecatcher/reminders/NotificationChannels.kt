package org.eu.angsumanray.recipecatcher.reminders

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import org.eu.angsumanray.recipecatcher.core.planner.ReminderType

/** One channel per alert kind, so each can also be silenced from the system settings. */
object NotificationChannels {
    const val PREP_REMINDERS = "prep_reminders"
    const val COOK_REMINDERS = "cook_reminders"

    fun channelFor(type: ReminderType): String = when (type) {
        ReminderType.PREP_NIGHT_BEFORE -> PREP_REMINDERS
        ReminderType.START_COOKING -> COOK_REMINDERS
    }

    fun ensure(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(PREP_REMINDERS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Night-before prep")
                    .setDescription("Prep entries marked \"prep the night before\"")
                    .build(),
                NotificationChannelCompat.Builder(COOK_REMINDERS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Start cooking")
                    .setDescription("Cook entries in slots with a time")
                    .build()
            )
        )
    }
}
