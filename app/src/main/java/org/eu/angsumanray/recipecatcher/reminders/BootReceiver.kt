package org.eu.angsumanray.recipecatcher.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.widget.TodayWidget

/**
 * Restores alarms after a reboot or app update and recomputes them when the clock or time zone
 * changes. Exported only for these system broadcasts; the app's own actions go to [ReminderReceiver].
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return

        val scheduler = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReminderReceiver.ReminderReceiverEntryPoint::class.java
        ).reminderScheduler()

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                scheduler.rescheduleAll()
                TodayWidget.refresh(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )
    }
}
