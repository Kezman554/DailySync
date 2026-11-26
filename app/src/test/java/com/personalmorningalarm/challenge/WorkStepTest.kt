package com.personalmorningalarm.challenge

import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.WorkDayDto
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.remote.AlfredClient
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResponseCache
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.AlfredSettings
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.time.LocalDate
import java.util.Collections

/**
 * When the alarm's Work step appears: Nick's profile, a work day (the hub's word
 * for today, else Monday to Friday), and something open on the work list. Jess's
 * profile never gets it and never asks the hub anything for it.
 */
@RunWith(RobolectricTestRunner::class)
class WorkStepTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    // 2026-11-26 is a Thursday; 2026-11-28 a Saturday.
    private val thursday = LocalDate.parse("2026-11-26")
    private val saturday = LocalDate.parse("2026-11-28")

    private fun answer(date: LocalDate, workDay: Boolean, source: String = "plan"): AlfredResult<WorkDayDto> =
        AlfredResult.Fresh(WorkDayDto(date.toString(), workDay, source), 0L)

    // --- is today a work day? ---

    @Test
    fun `the plan's word for today decides, either way, weekday or weekend`() {
        assertTrue(WorkStep.isWorkDay(answer(thursday, true), thursday))
        assertFalse("a day off in the plan", WorkStep.isWorkDay(answer(thursday, false), thursday))
        assertTrue("a Saturday shift in the plan", WorkStep.isWorkDay(answer(saturday, true), saturday))
    }

    @Test
    fun `the hub's own fallback answer is taken as given`() {
        assertTrue(WorkStep.isWorkDay(answer(thursday, true, "fallback"), thursday))
        assertFalse(WorkStep.isWorkDay(answer(saturday, false, "fallback"), saturday))
    }

    @Test
    fun `with no answer at all, Monday to Friday`() {
        val weekdays = (0L..4L).map { LocalDate.parse("2026-11-23").plusDays(it) }
        weekdays.forEach { assertTrue("$it", WorkStep.isWorkDay(AlfredResult.Unavailable, it)) }
        assertFalse(WorkStep.isWorkDay(AlfredResult.Unavailable, saturday))
        assertFalse(WorkStep.isWorkDay(null, saturday.plusDays(1)))
        assertTrue(WorkStep.isWorkDay(null, thursday))
    }

    @Test
    fun `a saved answer about another day says nothing about today`() {
        // Yesterday's "day off", served from the cache, on a Thursday: Monday to Friday.
        val yesterdayOff = AlfredResult.Stale(WorkDayDto(thursday.minusDays(1).toString(), false, "plan"), 0L)
        assertTrue(WorkStep.isWorkDay(yesterdayOff, thursday))
        // Friday's "work day", saved, read on the Saturday.
        val fridayOn = AlfredResult.Stale(WorkDayDto(saturday.minusDays(1).toString(), true, "plan"), 0L)
        assertFalse(WorkStep.isWorkDay(fridayOn, saturday))
        // Today's own answer, saved earlier this morning, still counts.
        assertFalse(WorkStep.isWorkDay(AlfredResult.Stale(WorkDayDto(thursday.toString(), false, "plan"), 0L), thursday))
    }

    @Test
    fun `an answer missing its verdict falls back too`() {
        assertTrue(WorkStep.isWorkDay(AlfredResult.Fresh(WorkDayDto(thursday.toString(), null, null), 0L), thursday))
    }

    // --- what it lists ---

    private val open = ChalkboardTaskDto("Send the [[release-notes|release notes]]", "2026-11-20", "- [ ] Send the [[release-notes|release notes]] (2026-11-20)", false)
    private val ticked = ChalkboardTaskDto("Rota review", "2026-11-19", "- [x] Rota review (2026-11-19)", true)

    @Test
    fun `open items only, vault markup resolved`() {
        val items = WorkStep.openItems(AlfredResult.Fresh(listOf(ticked, open), 0L))

        assertEquals(listOf("Send the release notes"), items?.map { it.task })
    }

    @Test
    fun `an empty list, or nothing left open, skips the step`() {
        assertNull(WorkStep.openItems(AlfredResult.Fresh(emptyList(), 0L)))
        assertNull(WorkStep.openItems(AlfredResult.Fresh(listOf(ticked), 0L)))
        assertNull(WorkStep.openItems(AlfredResult.Unavailable))
        assertNull(WorkStep.openItems(null))
    }

    // --- against a hub ---

    private val server = MockWebServer()
    private val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var workDayBody = """{"date":"2026-11-26","work_day":true,"source":"plan"}"""
    @Volatile private var workListBody = """[{"task":"Send the release notes","line":"- [ ] Send the release notes (2026-11-20)","date":"2026-11-20","ticked":false}]"""

    @Before
    fun setUp() {
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path
                return when (request.requestUrl.encodedPath()) {
                    "/daily-schedule/work-day" -> MockResponse().setBody(workDayBody)
                    "/chalkboard" -> MockResponse().setBody(workListBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        })
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        AlfredSettings(context).setHost("127.0.0.1:${server.port}")
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        AlfredReachability.reset()
    }

    private fun repository(profile: Profile?) = AlfredRepository(
        AlfredSettings(context),
        AlfredResponseCache(context),
        { AlfredClient.service(it) { profile } },
        profile = { profile }
    )

    private fun load(profile: Profile?, today: LocalDate = thursday) =
        runBlocking { WorkStep.load(repository(profile), profile, today) }

    @Test
    fun `on a work day Nick gets his open work items, asked for by name`() {
        val content = load(Profile.NICK)

        assertEquals(listOf("Send the release notes"), content?.items?.map { it.task })
        assertEquals(
            listOf("/chalkboard?list=work&include_ticked=true", "/daily-schedule/work-day"),
            paths.sorted()
        )
    }

    @Test
    fun `on a day off the work list isn't even fetched`() {
        workDayBody = """{"date":"2026-11-26","work_day":false,"source":"plan"}"""

        assertNull(load(Profile.NICK))
        assertEquals(listOf("/daily-schedule/work-day"), paths.toList())
    }

    @Test
    fun `an empty work list on a work day skips the step`() {
        workListBody = "[]"

        assertNull(load(Profile.NICK))
    }

    @Test
    fun `with the Pi unreachable the saved work list is used, and the day falls back to Monday to Friday`() {
        assertNotNull(load(Profile.NICK)) // saves both answers
        server.shutdown()

        val content = load(Profile.NICK)
        assertEquals(listOf("Send the release notes"), content?.items?.map { it.task })
        assertTrue(content?.list is AlfredResult.Stale)

        // Saturday, unreachable: today's saved answer is Thursday's, so the weekend rule holds.
        assertNull(load(Profile.NICK, saturday))
    }

    @Test
    fun `with the Pi unreachable and nothing saved, a weekday still can't show an empty step`() {
        server.shutdown()

        assertNull(load(Profile.NICK))
    }

    @Test
    fun `Jess's profile never gets the step and asks the hub nothing for it`() {
        assertNull(load(Profile.JESS))
        assertNull(load(Profile.JESS, saturday))
        assertNull(load(null))

        assertTrue(paths.toString(), paths.isEmpty())
    }
}
