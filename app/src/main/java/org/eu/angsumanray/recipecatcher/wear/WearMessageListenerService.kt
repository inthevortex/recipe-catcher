package org.eu.angsumanray.recipecatcher.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.data.repo.PlannerRepository
import org.eu.angsumanray.recipecatcher.reminders.ReminderScheduler
import org.eu.angsumanray.recipecatcher.widget.TodayWidget
import javax.inject.Inject

/**
 * Listens for messages sent from the Wear OS watch, e.g. marking a meal done.
 */
@AndroidEntryPoint
class WearMessageListenerService : WearableListenerService() {

    @Inject
    lateinit var plannerRepository: PlannerRepository

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    @Inject
    lateinit var wearSync: WearSync

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        if (path == PATH_DONE || path == PATH_TOGGLE) {
            val payload = messageEvent.data.decodeToString()
            val entryId = payload.toLongOrNull() ?: return
            serviceScope.launch {
                try {
                    if (path == PATH_DONE) {
                        plannerRepository.markEntryDone(entryId)
                    } else {
                        plannerRepository.toggleEntryDone(entryId)
                    }
                    reminderScheduler.rescheduleAll()
                    TodayWidget.refresh(applicationContext)
                    wearSync.publishToday()
                } catch (e: Exception) {
                    // Ignore or log error
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val PATH_DONE = "/done"
        const val PATH_TOGGLE = "/toggle"
    }
}
