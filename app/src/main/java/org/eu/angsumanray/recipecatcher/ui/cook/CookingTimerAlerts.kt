package org.eu.angsumanray.recipecatcher.ui.cook

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Posts the "timer done" notification, so a finished step timer is noticed even with the app in the background. */
@Singleton
class CookingTimerAlerts @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun timerFinished(recipeTitle: String?, stepNumber: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Cooking timers")
                .build()
        )

        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Step $stepNumber timer done")
            .setContentText(recipeTitle ?: "Cooking timer finished")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        try {
            manager.notify(NOTIFICATION_ID_BASE + stepNumber, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    private companion object {
        const val CHANNEL_ID = "cooking_timers"
        const val NOTIFICATION_ID_BASE = 4200
    }
}
