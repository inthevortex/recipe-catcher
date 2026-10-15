package org.eu.angsumanray.recipecatcher.reminders

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.data.db.dao.PlannerDao
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import org.eu.angsumanray.recipecatcher.widget.TodayWidget
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps alarms and the Today widget in step with the plan: any change to slots, entries or the
 * alert settings, from any screen, reschedules reminders and redraws the widget.
 */
@Singleton
class PlanChangeObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val plannerDao: PlannerDao,
    private val settingsStore: SettingsStore,
    private val reminderScheduler: ReminderScheduler,
    private val wearSync: org.eu.angsumanray.recipecatcher.wear.WearSync
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(
                plannerDao.observeAllSlots(),
                plannerDao.observeAllEntries(),
                settingsStore.prepAlertEnabledFlow,
                settingsStore.prepAlertTimeFlow,
                settingsStore.cookingAlertsEnabledFlow
            ) { _, _, _, _, _ -> }
                .debounce(DEBOUNCE_MILLIS)
                .collect {
                    runCatching {
                        reminderScheduler.rescheduleAll()
                        TodayWidget.refresh(context)
                        wearSync.publishToday()
                    }
                }
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 500L
    }
}
