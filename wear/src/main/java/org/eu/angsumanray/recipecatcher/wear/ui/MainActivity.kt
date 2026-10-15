package org.eu.angsumanray.recipecatcher.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.MaterialTheme
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.wear.data.TodayRepository

class MainActivity : ComponentActivity() {

    private lateinit var repository: TodayRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = TodayRepository.getInstance(applicationContext)

        handleIntent()

        setContent {
            MaterialTheme {
                TodayScreen(repository = repository)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            repository.flushPendingTicks()
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
