package org.eu.angsumanray.recipecatcher

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.eu.angsumanray.recipecatcher.reminders.PlanChangeObserver
import javax.inject.Inject

@HiltAndroidApp
class RecipeCatcherApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var planChangeObserver: PlanChangeObserver

    override fun onCreate() {
        super.onCreate()
        planChangeObserver.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
