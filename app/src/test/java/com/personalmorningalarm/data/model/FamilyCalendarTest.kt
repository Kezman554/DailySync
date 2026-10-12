package com.personalmorningalarm.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * The family calendar's bucketing and its merge with the vault plan. The wake page
 * renders off this, so every malformed shape here must come back as "no events"
 * rather than as an exception.
 */
class FamilyCalendarTest {

    private fun events(vararg events: CalendarEventDto) =
        CalendarEventsDto("young_family", events.toList())

    private fun allDay(summary: String, start: String, end: String?) =
        CalendarEventDto(summary, start, end, true, null, null)

    private fun timed(summary: String, start: String) =
        CalendarEventDto(summary, start, null, false, null, null)

    private val jul24 = LocalDate.parse("2026-07-24")
    private val jul25 = LocalDate.parse("2026-07-25")

    // --- all-day events and the exclusive end ---

    @Test
    fun `a one-day all-day event shows on its start date only`() {
        // The live France event: 24 Jul, ending "2026-07-25" exclusive.
        val byDate = FamilyCalendar.byDate(events(allDay("France", "2026-07-24", "2026-07-25")))

        assertEquals(listOf("France"), byDate[jul24]?.map { it.summary })
        assertNull("must not spill onto the 25th", byDate[jul25])
    }

    @Test
    fun `an all-day event carries no time`() {
        val event = FamilyCalendar.byDate(events(allDay("France", "2026-07-24", "2026-07-25")))[jul24]!!.single()

        assertTrue(event.allDay)
        assertNull(event.startTime)
    }

    @Test
    fun `a multi-day all-day event covers every day up to but not including the end`() {
        val byDate = FamilyCalendar.byDate(events(allDay("France", "2026-07-24", "2026-07-27")))

        assertEquals(
            listOf("2026-07-24", "2026-07-25", "2026-07-26").map(LocalDate::parse),
            byDate.keys.sorted()
        )
    }

    @Test
    fun `an all-day event with no end shows on its start date`() {
        val byDate = FamilyCalendar.byDate(events(allDay("France", "2026-07-24", null)))

        assertEquals(listOf(jul24), byDate.keys.toList())
    }

    @Test
    fun `an absurd range is treated as malformed, not as a year of banners`() {
        val byDate = FamilyCalendar.byDate(events(allDay("Glitch", "2026-07-24", "2099-01-01")))

        assertEquals(listOf(jul24), byDate.keys.toList())
    }

    @Test
    fun `a date-only start with no all_day flag is still treated as all-day`() {
        // Rendering it as "00:00" would be a worse guess than the shape already gives.
        val dto = CalendarEventsDto("young_family", listOf(
            CalendarEventDto("France", "2026-07-24", "2026-07-25", null, null, null)
        ))

        val event = FamilyCalendar.byDate(dto)[jul24]!!.single()
        assertTrue(event.allDay)
        assertNull(event.startTime)
    }

    // --- timed events ---

    @Test
    fun `a timed event keeps its wall-clock start`() {
        val event = FamilyCalendar
            .byDate(events(timed("Early shift", "2026-07-24T07:30:00+01:00")))[jul24]!!
            .single()

        assertFalse(event.allDay)
        assertEquals(LocalTime.of(7, 30), event.startTime)
    }

    @Test
    fun `a timed event without an offset still parses`() {
        val event = FamilyCalendar
            .byDate(events(timed("Swimming club", "2026-07-24T17:15:00")))[jul24]!!
            .single()

        assertEquals(LocalTime.of(17, 15), event.startTime)
    }

    @Test
    fun `a day's events come back all-day first, then in start order`() {
        val byDate = FamilyCalendar.byDate(
            events(
                timed("Swimming", "2026-07-24T17:15:00"),
                timed("Early shift", "2026-07-24T07:30:00"),
                allDay("France", "2026-07-24", "2026-07-25")
            )
        )

        assertEquals(listOf("France", "Early shift", "Swimming"), byDate[jul24]?.map { it.summary })
    }

    // --- malformed input is an absent calendar, never a crash ---

    @Test
    fun `a null response is an empty calendar`() {
        assertTrue(FamilyCalendar.byDate(null).isEmpty())
    }

    @Test
    fun `an event with no title is dropped`() {
        val byDate = FamilyCalendar.byDate(events(allDay("   ", "2026-07-24", "2026-07-25")))

        assertTrue(byDate.isEmpty())
    }

    @Test
    fun `an unparseable date is dropped rather than thrown`() {
        val byDate = FamilyCalendar.byDate(
            events(timed("Nonsense", "not-a-date"), allDay("Also nonsense", "sometime", "later"))
        )

        assertTrue(byDate.isEmpty())
    }

    // --- merging with the vault plan ---

    private fun task(text: String, period: String? = null) = ScheduleTaskDto(text, period)

    private fun event(summary: String, time: LocalTime?) =
        FamilyEvent(summary, jul24, time, allDay = time == null)

    @Test
    fun `a timed event lands in the period its start time falls in`() {
        val agenda = FamilyCalendar.agenda(
            listOf(task("A33 loop")),
            listOf(
                event("Early shift", LocalTime.of(7, 30)),
                event("School run", LocalTime.of(15, 0)),
                event("Club", LocalTime.of(19, 0))
            )
        )

        assertEquals(
            listOf(SchedulePeriod.EARLY, SchedulePeriod.AFTERNOON, SchedulePeriod.EVENING, SchedulePeriod.UNGROUPED),
            agenda.groups.map { it.period }
        )
    }

    @Test
    fun `period boundaries match the API - 09 00, noon and 17 00`() {
        val cases = mapOf(
            LocalTime.of(0, 0) to SchedulePeriod.EARLY,
            LocalTime.of(8, 59) to SchedulePeriod.EARLY,
            LocalTime.of(9, 0) to SchedulePeriod.MORNING,
            LocalTime.of(11, 59) to SchedulePeriod.MORNING,
            LocalTime.of(12, 0) to SchedulePeriod.AFTERNOON,
            LocalTime.of(16, 59) to SchedulePeriod.AFTERNOON,
            LocalTime.of(17, 0) to SchedulePeriod.EVENING,
            LocalTime.of(23, 59) to SchedulePeriod.EVENING
        )
        cases.forEach { (time, period) -> assertEquals("at $time", period, FamilyCalendar.periodOf(time)) }
    }

    @Test
    fun `a 17 30 event sits with the vault's Evening items, as on the wall`() {
        val agenda = FamilyCalendar.agenda(
            listOf(task("Cubs", "eve")),
            listOf(event("Swimming", LocalTime.of(17, 30)))
        )

        val evening = agenda.groups.single()
        assertEquals(SchedulePeriod.EVENING, evening.period)
        assertEquals(listOf("Swimming", "Cubs"), evening.entries.map { it.text })
    }

    @Test
    fun `timed events in one period sort by start time`() {
        val agenda = FamilyCalendar.agenda(
            emptyList(),
            listOf(event("Later", LocalTime.of(11, 0)), event("Earlier", LocalTime.of(9, 30)))
        )

        assertEquals(
            listOf("Earlier", "Later"),
            agenda.groups.single().entries.map { it.text }
        )
    }

    @Test
    fun `calendar events and vault blocks share a period, calendar first`() {
        // The vault's blocks carry no clock time, so the timed events lead.
        val agenda = FamilyCalendar.agenda(
            listOf(task("Weigh-in", "am"), task("Sweep verdict", "am")),
            listOf(event("Early shift", LocalTime.of(9, 30)))
        )

        val morning = agenda.groups.single { it.period == SchedulePeriod.MORNING }
        assertEquals(listOf("Early shift", "Weigh-in", "Sweep verdict"), morning.entries.map { it.text })
        assertEquals(listOf(true, false, false), morning.entries.map { it.fromCalendar })
    }

    @Test
    fun `all-day events sit outside the periods`() {
        val agenda = FamilyCalendar.agenda(listOf(task("A33 loop")), listOf(event("France", null)))

        assertEquals(listOf("France"), agenda.allDay.map { it.summary })
        assertTrue(agenda.groups.none { group -> group.entries.any { it.fromCalendar } })
    }

    @Test
    fun `a day with no plan and no events is empty`() {
        assertTrue(FamilyCalendar.agenda(emptyList(), emptyList()).isEmpty)
    }

    @Test
    fun `a day with only calendar events is not empty`() {
        assertFalse(FamilyCalendar.agenda(emptyList(), listOf(event("France", null))).isEmpty)
    }

    @Test
    fun `the vault plan renders unchanged when there is no calendar`() {
        val agenda = FamilyCalendar.agenda(listOf(task("A33 loop"), task("Weigh-in", "am")), emptyList())

        assertTrue(agenda.allDay.isEmpty())
        assertEquals(
            listOf(SchedulePeriod.MORNING, SchedulePeriod.UNGROUPED),
            agenda.groups.map { it.period }
        )
        assertTrue(agenda.groups.flatMap { it.entries }.none { it.fromCalendar })
    }
}
