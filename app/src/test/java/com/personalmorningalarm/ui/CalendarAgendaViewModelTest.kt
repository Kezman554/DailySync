package com.personalmorningalarm.ui

import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.model.CalendarEventDto
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.time.LocalDate
import java.util.Collections

/** The Calendar screen: four weeks from today in one call, and an added event shown at once. */
@RunWith(RobolectricTestRunner::class)
class CalendarAgendaViewModelTest : ViewModelTestSupport() {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val settings = AlfredSettings(context)
    private val server = MockWebServer()
    private val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val added = MutableSharedFlow<CalendarEventDto>(extraBufferCapacity = 1)

    private val today = LocalDate.of(2026, 11, 26)

    @Volatile
    private var calendarAnswers = true

    @Before
    fun setUp() {
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path!!
                return if (calendarAnswers) {
                    MockResponse().setBody("""{"calendar":"young_family","events":[]}""")
                } else {
                    MockResponse().setResponseCode(503)
                }
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

    private fun viewModel() = track(
        CalendarAgendaViewModel(AlfredRepository(settings, AlfredResponseCache(context)), added) { today }
    )

    @Test
    fun `one call covers four weeks from today`() {
        val vm = viewModel()
        awaitValue(vm.state) { it.calendar is AlfredResult.Fresh }

        assertEquals(listOf("/calendar/events?start=2026-11-26&end=2026-12-24"), paths.toList())
    }

    @Test
    fun `an added event shows at once, and stays if the refetch fails`() {
        val vm = viewModel()
        awaitValue(vm.state) { it.calendar is AlfredResult.Fresh }
        calendarAnswers = false

        added.tryEmit(
            CalendarEventDto("Jess: dentist", "2026-11-27T15:00:00+00:00", "2026-11-27T15:45:00+00:00", false, null, null)
        )
        awaitValue(vm.state) { paths.size == 2 }
        Thread.sleep(100)

        val agenda = HomeTiles.agenda((vm.state.value.calendar as AlfredResult.Fresh).data, today, 28)
        assertEquals(listOf(today.plusDays(1) to listOf("Jess: dentist")), agenda.map { (d, e) -> d to e.map { it.summary } })
    }

    @Test
    fun `no copy and no hub is unavailable, which the screen says plainly`() {
        calendarAnswers = false
        val vm = viewModel()

        assertEquals(AlfredResult.Unavailable, awaitValue(vm.state) { it.calendar != null }.calendar)
    }
}
