package org.eu.angsumanray.recipecatcher.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.wear.data.TodayRepository

class MainActivity : ComponentActivity() {

    private lateinit var repository: TodayRepository

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = TodayRepository.getInstance(applicationContext)

        handleIntent()
        requestNotificationPermission()

        setContent {
            MaterialTheme {
                AppScaffold {
                    TodayScreen(repository = repository)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            repository.flushPendingTicks()
        }
    }

    /** Update-available alerts from the phone are notifications, which need this grant on API 33+. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleIntent() {
        val entryId = intent.getLongExtra(EXTRA_TICK_ENTRY_ID, -1L)
        if (entryId > 0L) {
            lifecycleScope.launch {
                repository.markDone(entryId)
            }
        }
    }

    companion object {
        const val EXTRA_TICK_ENTRY_ID = "tick_entry_id"
    }
}
