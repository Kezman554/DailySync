package com.personalmorningalarm.data.remote

import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.personalmorningalarm.data.model.CalendarCreateRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.ServerSocket

/**
 * POST /calendar/events over a real socket. Every call makes an event, so the one
 * thing that must never happen is a second request the person didn't ask for, and
 * a failure must say whether anything reached the hub.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarCreateTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val settings = AlfredSettings(context)
    private val server = MockWebServer()
    private val repository = AlfredRepository(settings, AlfredResponseCache(context))

    private val timed = CalendarCreateRequest("dentist", "jess", start = "2026-11-25T15:00", end = "2026-11-25T15:45")

    @Before
    fun setUp() {
        server.start(InetAddress.getByName(LOCALHOST), 0)
        settings.setHost("$LOCALHOST:${server.port}")
    }

    @After
    fun tearDown() {
        server.shutdown()
        AlfredReachability.reset()
    }

    private fun created(summary: String = "Jess: dentist") = MockResponse().setResponseCode(201).setBody(
        """{"calendar":"young_family","events":[{"summary":"$summary","start":"2026-11-25T15:00:00+00:00",""" +
            """"end":"2026-11-25T15:45:00+00:00","all_day":false,"location":null,"description":null}]}"""
    )

    @Test
    fun `the request carries the bare title, who and times, and no date`() = runBlocking<Unit> {
        server.enqueue(created())

        repository.createCalendarEvent(timed)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/calendar/events", request.path)
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(setOf("title", "who", "start", "end"), body.keySet())
        assertEquals("dentist", body["title"].asString)
        assertEquals("jess", body["who"].asString)
        assertEquals("2026-11-25T15:00", body["start"].asString)
        assertEquals("2026-11-25T15:45", body["end"].asString)
    }

    @Test
    fun `an all-day event sends the date and no times`() = runBlocking<Unit> {
        server.enqueue(created())

        repository.createCalendarEvent(CalendarCreateRequest("Half term", "family", date = "2026-11-25"))

        val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals(setOf("title", "who", "date"), body.keySet())
    }

    @Test
    fun `201 is created, with the hub's copy of the event, and the Week screen hears of it`() = runBlocking {
        server.enqueue(created())
        val heard = async { withTimeoutOrNull(3_000) { CalendarChanges.added.first() } }
        // Let the listener subscribe before the event is announced.
        yield()

        val result = repository.createCalendarEvent(timed)

        val event = (result as CalendarCreateResult.Created).event
        assertEquals("Jess: dentist", event?.summary)
        assertEquals(event, heard.await())
        assertEquals(AlfredReachability.State.REACHABLE, AlfredReachability.state.value)
    }

    @Test
    fun `422 is refused, in the hub's own words`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"end must be after start"}"""))

        assertEquals(CalendarCreateResult.Refused("end must be after start"), repository.createCalendarEvent(timed))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a refusal whose detail isn't a sentence is refused without words`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":[{"loc":["body","title"]}]}"""))

        assertEquals(CalendarCreateResult.Refused(null), repository.createCalendarEvent(timed))
    }

    @Test
    fun `400 for an unknown who is refused`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"unknown who 'Jess'"}"""))

        assertTrue(repository.createCalendarEvent(timed) is CalendarCreateResult.Refused)
    }

    @Test
    fun `a fault on the hub is unconfirmed, not retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"detail":"upstream"}"""))
        server.enqueue(created())

        assertEquals(CalendarCreateResult.Unconfirmed, repository.createCalendarEvent(timed))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an answer lost after the request went out is unconfirmed, and OkHttp doesn't resend it`() = runBlocking {
        // The Week screen has just read the calendar, so the POST goes out on a pooled
        // connection: the case where OkHttp, without the one-shot body, quietly sends
        // it again and the second copy lands.
        server.enqueue(MockResponse().setBody("""{"calendar":"young_family","events":[]}"""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(created())
        repository.getCalendarWeek(java.time.LocalDate.of(2026, 11, 23), java.time.LocalDate.of(2026, 11, 29))

        assertEquals(CalendarCreateResult.Unconfirmed, repository.createCalendarEvent(timed))
        assertEquals("the read, then one POST", 2, server.requestCount)
    }

    @Test
    fun `a hub that can't be connected to is not sent`() = runBlocking {
        settings.setHost("$LOCALHOST:${closedPort()}")

        assertEquals(CalendarCreateResult.NotSent, repository.createCalendarEvent(timed))
        assertEquals(AlfredReachability.State.UNREACHABLE, AlfredReachability.state.value)
    }

    @Test
    fun `the marker stays clear when no connection is made, and is set once one is`() {
        val marker = SendMarker()
        settings.setHost("$LOCALHOST:${closedPort()}")
        runBlocking { runCatching { AlfredClient.service(settings) { null }.createCalendarEvent(timed, marker) } }
        assertFalse(marker.sent)

        settings.setHost("$LOCALHOST:${server.port}")
        server.enqueue(created())
        val reached = SendMarker()
        runBlocking { AlfredClient.service(settings) { null }.createCalendarEvent(timed, reached) }
        assertTrue(reached.sent)
    }

    @Test
    fun `a created answer with no event in it is still created`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"calendar":"young_family","events":[]}"""))

        val result = repository.createCalendarEvent(timed) as CalendarCreateResult.Created
        assertNull(result.event)
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val LOCALHOST = "127.0.0.1"
    }
}
