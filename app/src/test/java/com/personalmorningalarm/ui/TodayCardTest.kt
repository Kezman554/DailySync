package com.personalmorningalarm.ui

import com.personalmorningalarm.data.model.CalendarEventDto
import com.personalmorningalarm.data.model.CalendarEventsDto
import com.personalmorningalarm.data.remote.AlfredResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Jess's Today card: today's family events in order, and never blank when the hub is away. */
class TodayCardTest {

    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 11, 26)
    private val savedToday = today.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
    private val savedYesterday = today.minusDays(1).atTime(21, 0).atZone(zone).toInstant().toEpochMilli()

    private fun timed(summary: String, start: String, end: String) =
        CalendarEventDto(summary, "${today}T$start:00+00:00", "${today}T$end:00+00:00", false, null, null)

    private fun allDay(summary: String, start: LocalDate = today, endExclusive: LocalDate = today.plusDays(1)) =
        CalendarEventDto(summary, start.toString(), endExclusive.toString(), true, null, null)

    private fun calendar(vararg events: CalendarEventDto) = CalendarEventsDto("young_family", events.toList())

    private fun shown(today: HomeTiles.Today): List<Pair<LocalTime?, String>> =
        (today as HomeTiles.Today.Events).events.map { it.startTime to it.summary }

    @Test
    fun `all-day events come first, then timed ones in start order`() {
        val result = AlfredResult.Fresh(
            calendar(
                timed("Jess: dentist", "15:00", "15:45"),
                allDay("Bin day"),
                timed("Oliver: swimming", "08:30", "09:30"),
                allDay("Half term", start = today.minusDays(2), endExclusive = today.plusDays(3)),
                timed("Nick: haircut", "12:00", "12:30")
            ),
            savedToday
        )

        assertEquals(
            listOf(
                null to "Bin day",
                null to "Half term",
                LocalTime.of(8, 30) to "Oliver: swimming",
                LocalTime.of(12, 0) to "Nick: haircut",
                LocalTime.of(15, 0) to "Jess: dentist"
            ),
            shown(HomeTiles.today(result, today, zone))
        )
    }

    @Test
    fun `only today's events, not the days either side`() {
        val tomorrow = today.plusDays(1)
        val result = AlfredResult.Fresh(
            calendar(
                timed("Jess: dentist", "15:00", "15:45"),
                CalendarEventDto("Tomorrow's thing", "${tomorrow}T09:00:00+00:00", "${tomorrow}T10:00:00+00:00", false, null, null),
                allDay("Yesterday's", start = today.minusDays(1), endExclusive = today)
            ),
            savedToday
        )

        assertEquals(listOf(LocalTime.of(15, 0) to "Jess: dentist"), shown(HomeTiles.today(result, today, zone)))
    }

    @Test
    fun `a day with nothing on is empty, not unreachable`() {
        assertEquals(
            HomeTiles.Today.Events(emptyList()),
            HomeTiles.today(AlfredResult.Fresh(calendar(), savedToday), today, zone)
        )
    }

    @Test
    fun `not asked yet is loading`() {
        assertEquals(HomeTiles.Today.Loading, HomeTiles.today(null, today, zone))
    }

    @Test
    fun `no copy and no hub says the hub is out of reach`() {
        assertEquals(HomeTiles.Today.Unreachable, HomeTiles.today(AlfredResult.Unavailable, today, zone))
    }

    @Test
    fun `a copy saved earlier today is shown while the hub is away`() {
        val stale = AlfredResult.Stale(calendar(timed("Jess: dentist", "15:00", "15:45")), savedToday)

        assertEquals(listOf(LocalTime.of(15, 0) to "Jess: dentist"), shown(HomeTiles.today(stale, today, zone)))
    }

    @Test
    fun `yesterday's copy is unreachable, never an empty day`() {
        // Saved yesterday, its events are yesterday's: offline it would read as "nothing on".
        val stale = AlfredResult.Stale(
            CalendarEventsDto("young_family", listOf(allDay("Yesterday's", today.minusDays(1), today))),
            savedYesterday
        )

        assertEquals(HomeTiles.Today.Unreachable, HomeTiles.today(stale, today, zone))
    }

    @Test
    fun `the agenda lists days with anything on, in date order, from today for the range`() {
        val dto = calendar(
            CalendarEventDto("Later", "2026-12-01T10:00:00+00:00", "2026-12-01T11:00:00+00:00", false, null, null),
            timed("Today's", "09:00", "10:00"),
            allDay("Past", today.minusDays(3), today.minusDays(2)),
            CalendarEventDto("Too far", "2027-01-20T10:00:00+00:00", "2027-01-20T11:00:00+00:00", false, null, null),
            allDay("Weekend", LocalDate.of(2026, 11, 28), LocalDate.of(2026, 11, 30))
        )

        val agenda = HomeTiles.agenda(dto, today, 28)

        assertEquals(
            listOf(today, LocalDate.of(2026, 11, 28), LocalDate.of(2026, 11, 29), LocalDate.of(2026, 12, 1)),
            agenda.map { it.first }
        )
        assertEquals(listOf("Weekend"), agenda[1].second.map { it.summary })
    }
}
