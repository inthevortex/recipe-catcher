package org.eu.angsumanray.recipecatcher.ui.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportProgressTest {

    private val now = 1_000_000_000L

    @Test
    fun estimate_isThirtySecondsPerQueuedPost() {
        val state = ImportViewModel.computeProgress(
            queued = 300, done = 0, skipped = 0, failed = 0, paused = false, cooldownUntil = 0L, now = now
        )

        assertEquals(150, state.estimatedFinishMinutes)
        assertEquals(now + 300 * 30_000L, state.estimatedFinishAtMillis)
        assertFalse(state.isUnderCooldown)
    }

    @Test
    fun estimate_includesRemainingCooldown() {
        val state = ImportViewModel.computeProgress(
            queued = 2, done = 3, skipped = 0, failed = 3, paused = false,
            cooldownUntil = now + 10 * 60_000L, now = now
        )

        assertTrue(state.isUnderCooldown)
        assertEquals(600L, state.cooldownRemainingSeconds)
        assertEquals(11, state.estimatedFinishMinutes)
    }

    @Test
    fun estimate_expiredCooldownAndEmptyQueue() {
        val state = ImportViewModel.computeProgress(
            queued = 0, done = 5, skipped = 1, failed = 0, paused = false, cooldownUntil = now - 1L, now = now
        )

        assertFalse(state.isUnderCooldown)
        assertEquals(0L, state.cooldownRemainingSeconds)
        assertEquals(0, state.estimatedFinishMinutes)
        assertNull(state.estimatedFinishAtMillis)
    }
}
