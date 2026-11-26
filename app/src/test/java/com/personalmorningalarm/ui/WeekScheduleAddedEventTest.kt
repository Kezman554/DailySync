package com.personalmorningalarm.ui

import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.model.CalendarEventDto
import com.personalmorningalarm.data.model.CalendarEventsDto
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResponseCache
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.AlfredSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.time.LocalDate

/** A Young Family event added on the Add Event screen shows on the Week screen without a refresh. */
@RunWith(RobolectricTestRunner::class)
class WeekScheduleAddedEventTest : ViewModelTestSupport() {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val settings = AlfredSettings(context)
    private val server = MockWebServer()
    private val added = MutableSharedFlow<CalendarEventDto>(extraBufferCapacity = 1)

    private val wed = LocalDate.of(2026, 11, 25)
    private val dentist = CalendarEventDto("Jess: dentist", "2026-11-25T15:00:00+00:00", "2026-11-25T15:45:00+00:00", false, null, null)

    /** What GET /calendar/events answers: its body, or null for a failure. */
    @Volatile
    private var calendarBody: String? = """{"calendar":"young_family","events":[]}"""

    @Volatile
    private var calendarCalls = 0

    @Before
    fun setUp() {
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/daily-schedule/week") -> MockResponse().setBody(
                    """{"week":"2026-W48","start":"2026-11-23","end":"2026-11-28",""" +
                        """"days":{"2026-11-23":[],"2026-11-24":[],"2026-11-25":[],"2026-11-26":[],"2026-11-27":[],"2026-11-28":[]}}"""
                )
                request.path!!.startsWith("/calendar/events") -> {
                    calendarCalls++
                    calendarBody?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(500)
                }
                else -> MockResponse().setResponseCode(404)
            }
        })
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        settings.setHost("127.0.0.1:${server.port}")
    }

    @After
    fun tearDown() {
        server.shutdown()
        AlfredReachability.reset()
    }

    private fun viewModel() = track(WeekScheduleViewModel(AlfredRepository(settings, AlfredResponseCache(context)), added))

    private fun events(state: WeekScheduleViewModel.WeekState) = state.eventsByDate[wed].orEmpty().map { it.summary }

    @Test
    fun `an added event shows, and the calendar is fetched again`() {
        val vm = viewModel()
        awaitValue(vm.state) { it.calendar is AlfredResult.Fresh }
        calendarBody = """{"calendar":"young_family","events":[{"summary":"Jess: dentist",""" +
            """"start":"2026-11-25T15:00:00+00:00","end":"2026-11-25T15:45:00+00:00","all_day":false}]}"""

        added.tryEmit(dentist)

        assertEquals(listOf("Jess: dentist"), events(vm.state.value))
        awaitValue(vm.state) { calendarCalls == 2 && it.calendar is AlfredResult.Fresh }
        assertEquals(listOf("Jess: dentist"), events(vm.state.value))
    }

    @Test
    fun `if the refetch fails, the added event stays rather than the saved copy replacing it`() {
        val vm = viewModel()
        awaitValue(vm.state) { it.calendar is AlfredResult.Fresh }
        calendarBody = null

        added.tryEmit(dentist)
        awaitValue(vm.state) { calendarCalls == 2 }
        Thread.sleep(100)
        scheduler.runCurrent()

        assertEquals(listOf("Jess: dentist"), events(vm.state.value))
        assert(vm.state.value.calendar is AlfredResult.Fresh)
    }

    @Test
    fun `the screen's factory can build it`() {
        // The Week screen gets its ViewModel by reflection on a one-argument constructor.
        val repository = AlfredRepository(settings, AlfredResponseCache(context))
        track(AlfredViewModelFactory(repository).create(WeekScheduleViewModel::class.java))
    }

    @Test
    fun `an event is added to the calendar on screen, keeping its age`() {
        val stale = AlfredResult.Stale(CalendarEventsDto("young_family", emptyList()), cachedAtMillis = 1_000)

        val merged = WeekScheduleViewModel.withEvent(stale, dentist) as AlfredResult.Stale

        assertEquals(1_000, merged.cachedAtMillis)
        assertEquals(listOf(dentist), merged.data.events)
    }

    @Test
    fun `with no calendar on screen nothing is made up`() {
        assertNull(WeekScheduleViewModel.withEvent(null, dentist))
        assertEquals(AlfredResult.Unavailable, WeekScheduleViewModel.withEvent(AlfredResult.Unavailable, dentist))
    }
}
