package com.personalmorningalarm.ui

import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.model.PresenceStatusDto
import com.personalmorningalarm.data.remote.DashboardApiService
import com.personalmorningalarm.data.remote.DashboardSettings
import com.personalmorningalarm.data.remote.PresencePinRequest
import com.personalmorningalarm.data.remote.PresenceRepository
import com.personalmorningalarm.ui.PresenceViewModel.PresenceEvent
import com.personalmorningalarm.ui.PresenceViewModel.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response
import java.io.IOException
import java.util.Collections

/**
 * The sign-in tile's behaviour against a fake dashboard: refusals are reported,
 * the session shown is the one the dashboard last gave, and an unreachable
 * dashboard fails the attempt without anything being retried later.
 */
@RunWith(RobolectricTestRunner::class)
class PresenceViewModelTest : ViewModelTestSupport() {

    /** A tiny fake dashboard: a PIN, a session flag and a reachability switch. */
    private inner class FakeDashboard(private val pin: String) : DashboardApiService {
        @Volatile var reachable = true
        @Volatile var isOpen = false
        val pinsReceived: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private fun status() = PresenceStatusDto(
            open = isOpen,
            person = if (isOpen) "nick" else null,
            expiresInSeconds = if (isOpen) 300.0 else null
        )

        private fun reach() {
            if (!reachable) throw IOException("Pi unreachable")
        }

        override suspend fun getPresence(): Response<PresenceStatusDto> {
            reach()
            return Response.success(status())
        }

        override suspend fun openPresence(body: PresencePinRequest): Response<PresenceStatusDto> {
            reach()
            pinsReceived += body.pin
            if (body.pin != pin) {
                return Response.error(
                    401,
                    ResponseBody.create(MediaType.parse("application/json"), """{"detail":"Wrong PIN"}""")
                )
            }
            isOpen = true
            return Response.success(status())
        }

        override suspend fun closePresence(): Response<PresenceStatusDto> {
            reach()
            isOpen = false
            return Response.success(status())
        }
    }

    private val dashboard = FakeDashboard(pin = "2468")
    private val settings =
        DashboardSettings(ApplicationProvider.getApplicationContext<android.app.Application>())

    private val events: MutableList<PresenceEvent> = Collections.synchronizedList(mutableListOf())
    private val eventScope = CoroutineScope(Dispatchers.Default + Job())

    private fun viewModel(): PresenceViewModel {
        val vm = track(PresenceViewModel(PresenceRepository(settings) { dashboard }))
        eventScope.launch { vm.events.collect { events += it } }
        return vm
    }

    @After
    fun tearDown() {
        eventScope.cancel()
    }

    private fun awaitEvent(expected: PresenceEvent) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (expected in events) return
            Thread.sleep(15)
        }
        throw AssertionError("Never saw $expected; events = $events")
    }

    @Test
    fun `reads a session opened elsewhere on refresh`() {
        dashboard.isOpen = true
        val vm = viewModel()

        vm.refresh()

        val state = awaitValue(vm.state) { it.session is Session.Open }
        assertEquals("nick", (state.session as Session.Open).person)
    }

    @Test
    fun `a wrong PIN is refused and opens nothing`() {
        val vm = viewModel()
        vm.refresh()
        awaitValue(vm.state) { it.session == Session.Closed }

        vm.signIn("1111")

        awaitEvent(PresenceEvent.WrongPin)
        assertFalse(dashboard.isOpen)
        assertEquals(Session.Closed, vm.state.value.session)
    }

    @Test
    fun `the right PIN signs in, and sign-out closes it`() {
        val vm = viewModel()

        vm.signIn("2468")
        awaitEvent(PresenceEvent.SignedIn)
        awaitValue(vm.state) { it.session is Session.Open && !it.busy }
        assertTrue(dashboard.isOpen)

        vm.signOut()
        awaitEvent(PresenceEvent.SignedOut)
        awaitValue(vm.state) { it.session == Session.Closed }
        assertFalse(dashboard.isOpen)
    }

    @Test
    fun `an unreachable dashboard fails the sign-in and it never arrives later`() {
        dashboard.reachable = false
        val vm = viewModel()

        vm.signIn("2468")
        awaitEvent(PresenceEvent.Unreachable)
        awaitValue(vm.state) { !it.busy }

        // The Pi comes back and the screen re-reads, as it does on every visit.
        // A queued sign-in would flush here; this one must not exist.
        dashboard.reachable = true
        vm.refresh()
        awaitValue(vm.state) { it.session == Session.Closed && !it.loading }

        assertFalse(dashboard.isOpen)
        assertTrue(dashboard.pinsReceived.isEmpty())
        assertFalse(PresenceEvent.SignedIn in events)
    }

    @Test
    fun `an unreachable sign-out fails too, and is not replayed when the Pi returns`() {
        dashboard.isOpen = true
        val vm = viewModel()
        vm.refresh()
        awaitValue(vm.state) { it.session is Session.Open && !it.loading }

        dashboard.reachable = false
        vm.signOut()
        awaitEvent(PresenceEvent.Unreachable)
        awaitValue(vm.state) { !it.busy }

        dashboard.reachable = true
        vm.refresh()
        val state = awaitValue(vm.state) { it.session is Session.Open && !it.loading }

        // Still open on the wall: the failed close was reported, not saved for later.
        assertTrue(dashboard.isOpen)
        assertTrue(state.session is Session.Open)
        assertFalse(PresenceEvent.SignedOut in events)
    }

    @Test
    fun `an unreachable read shows unknown rather than a remembered state`() {
        dashboard.isOpen = true
        val vm = viewModel()
        vm.refresh()
        awaitValue(vm.state) { it.session is Session.Open && !it.loading }

        dashboard.reachable = false
        vm.refresh()

        val state = awaitValue(vm.state) { it.unreachable && !it.loading }
        assertEquals(Session.Unknown, state.session)
    }

    @Test
    fun `the PIN is not kept in the view model's state`() {
        val vm = viewModel()
        vm.signIn("2468")
        awaitEvent(PresenceEvent.SignedIn)

        assertFalse(vm.state.value.toString().contains("2468"))
    }
}
