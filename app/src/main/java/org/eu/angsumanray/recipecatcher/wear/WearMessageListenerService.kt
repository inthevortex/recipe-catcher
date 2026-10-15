package org.eu.angsumanray.recipecatcher.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import org.eu.angsumanray.recipecatcher.data.repo.PlannerRepository
import org.eu.angsumanray.recipecatcher.reminders.ReminderScheduler
import org.eu.angsumanray.recipecatcher.widget.TodayWidget
import javax.inject.Inject

/**
 * Applies ticks sent from the watch. Only "mark done" is accepted: the watch queues ticks while
 * offline and may resend one, so the message has to be idempotent.
 */
@AndroidEntryPoint
class WearMessageListenerService : WearableListenerService() {

    @Inject
    lateinit var plannerRepository: PlannerRepository

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    @Inject
    lateinit var wearSync: WearSync

    // Runs on a Play Services binder thread. Blocking keeps the service alive until the database
    // write and republish finish; a coroutine launched here could be cancelled when it unbinds.
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != PATH_DONE) return
        val entryId = parseEntryId(messageEvent.data) ?: run {
            Log.w(TAG, "Ignoring /done message without a valid entry id")
            return
        }
        runBlocking {
            try {
                plannerRepository.markEntryDone(entryId)
                reminderScheduler.rescheduleAll()
                TodayWidget.refresh(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply watch tick for entry $entryId", e)
            }
            // Republish even after a failure, so the watch drops its optimistic tick if it didn't apply.
            wearSync.publishToday()
        }
    }

    companion object {
        private const val TAG = "WearMessageListener"
        const val PATH_DONE = "/done"

        fun parseEntryId(payload: ByteArray?): Long? =
            payload?.decodeToString()?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    }
}
