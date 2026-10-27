package com.personalmorningalarm.service

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.Shadows.shadowOf

/**
 * The system restarts CountdownService with a null intent after the process was
 * killed mid-Stage 2. It must carry on to the saved deadline, not begin a fresh
 * countdown with no screen to answer it.
 */
@RunWith(RobolectricTestRunner::class)
class CountdownServiceRestartTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private var controller: ServiceController<CountdownService>? = null

    @Before
    fun clean() {
        save(deadlineMs = 0L, pausedSeconds = 0)
    }

    /** The remaining-seconds flow is process-wide; destroying the service resets it, as the system would. */
    @After
    fun tearDown() {
        controller?.destroy()
    }

    private fun save(deadlineMs: Long, pausedSeconds: Int) {
        app.getSharedPreferences("countdown_state", Context.MODE_PRIVATE).edit()
            .putLong("deadline_ms", deadlineMs)
            .putInt("paused_seconds", pausedSeconds)
            .commit()
    }

    private fun restart(): CountdownService {
        val built = Robolectric.buildService(CountdownService::class.java).create()
        controller = built
        val service = built.get()
        service.onStartCommand(null, 0, 1)
        return service
    }

    private fun nuclearStarted(): Boolean {
        val next: Intent? = shadowOf(app).nextStartedService
        return next?.component?.className == NuclearAlarmService::class.java.name
    }

    @Test
    fun `restart continues to the saved deadline rather than starting over`() {
        save(deadlineMs = System.currentTimeMillis() + 300_000L, pausedSeconds = 0)

        restart()

        val left = CountdownService.remainingSeconds.value
        assertTrue("expected about 300s left, got $left", left in 298..300)
        assertTrue(CountdownService.isRunning)
    }

    @Test
    fun `restart while paused for a content screen resumes the paused time`() {
        save(deadlineMs = 0L, pausedSeconds = 820)

        restart()

        assertEquals(820, CountdownService.remainingSeconds.value)
    }

    @Test
    fun `restart after the deadline passed recently fires the nuclear alarm`() {
        save(deadlineMs = System.currentTimeMillis() - 60_000L, pausedSeconds = 0)

        restart()

        assertTrue(nuclearStarted())
    }

    @Test
    fun `restart long after the deadline does nothing`() {
        save(deadlineMs = System.currentTimeMillis() - 3 * 60 * 60 * 1000L, pausedSeconds = 0)

        restart()

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `restart with no saved state does not start a fresh countdown`() {
        restart()

        assertNull(shadowOf(app).nextStartedService)
        assertEquals(0, CountdownService.remainingSeconds.value)
    }
}
