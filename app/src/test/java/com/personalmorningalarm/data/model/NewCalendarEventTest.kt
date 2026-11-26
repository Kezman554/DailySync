package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.profile.Profile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The Add Event screen's rules: what's refused before sending, and what's sent. */
class NewCalendarEventTest {

    private val day = LocalDate.of(2026, 11, 25)
    private val london = ZoneId.of("Europe/London")

    private fun draft(
        title: String = "Dentist",
        date: LocalDate = day,
        allDay: Boolean = false,
        start: LocalTime = LocalTime.of(15, 0),
        end: LocalTime = LocalTime.of(15, 45),
        calendar: EventCalendar = EventCalendar.FAMILY,
        who: EventWho = EventWho.JESS
    ) = EventDraft(title, date, allDay, start, end, calendar, who)

    private fun familyRequest(draft: EventDraft): CalendarCreateRequest =
        (NewCalendarEvent.validate(draft, london) as EventValidation.Family).request

    private fun problem(draft: EventDraft): EventProblem =
        (NewCalendarEvent.validate(draft, london) as EventValidation.Invalid).problem

    // --- what a family event sends ---

    @Test
    fun `a timed family event sends local times with no offset and the bare title`() {
        assertEquals(
            CalendarCreateRequest("Dentist", "jess", start = "2026-11-25T15:00", end = "2026-11-25T15:45"),
            familyRequest(draft())
        )
    }

    @Test
    fun `an all-day family event sends only the date`() {
        assertEquals(
            CalendarCreateRequest("Dentist", "jess", date = "2026-11-25"),
            familyRequest(draft(allDay = true))
        )
    }

    @Test
    fun `who goes as the wire name, family included`() {
        assertEquals("oliver", familyRequest(draft(who = EventWho.OLIVER)).who)
        assertEquals("family", familyRequest(draft(who = EventWho.FAMILY)).who)
        assertEquals("nick", familyRequest(draft(who = EventWho.NICK)).who)
    }

    @Test
    fun `the title is trimmed`() {
        assertEquals("Dentist", familyRequest(draft(title = "  Dentist  ")).title)
    }

    // --- the prefix is the server's to add ---

    @Test
    fun `the chosen person's typed prefix is taken off`() {
        assertEquals("dentist", familyRequest(draft(title = "Jess: dentist")).title)
        assertEquals("dentist", familyRequest(draft(title = "jess:dentist")).title)
        assertEquals("dentist", familyRequest(draft(title = "  JESS :  dentist")).title)
    }

    @Test
    fun `a prefix typed twice comes off both times`() {
        assertEquals("dentist", familyRequest(draft(title = "Jess: Jess: dentist")).title)
    }

    @Test
    fun `another person's prefix is part of the title`() {
        assertEquals("Oliver: swimming", familyRequest(draft(title = "Oliver: swimming", who = EventWho.JESS)).title)
    }

    @Test
    fun `a family event has no prefix to take off`() {
        assertEquals("Jess: dentist", familyRequest(draft(title = "Jess: dentist", who = EventWho.FAMILY)).title)
    }

    @Test
    fun `a name without the colon is not a prefix`() {
        assertEquals("Jess dentist", familyRequest(draft(title = "Jess dentist")).title)
        assertEquals("Jessica's party", familyRequest(draft(title = "Jessica's party")).title)
    }

    @Test
    fun `a title that is only the prefix is blank`() {
        assertEquals(EventProblem.BLANK_TITLE, problem(draft(title = "Jess:")))
    }

    // --- refused before sending, as vault-api would ---

    @Test
    fun `a blank title is refused`() {
        assertEquals(EventProblem.BLANK_TITLE, problem(draft(title = "   ")))
    }

    @Test
    fun `120 characters is fine and 121 is too long, counted after the prefix comes off`() {
        val longest = "a".repeat(120)
        assertEquals(longest, familyRequest(draft(title = "Jess: $longest")).title)
        assertEquals(EventProblem.TITLE_TOO_LONG, problem(draft(title = "a".repeat(121))))
    }

    @Test
    fun `characters are counted as the server counts them, not as UTF-16 units`() {
        // Each emoji is two UTF-16 units but one character to Python's len().
        val emoji = "🎂".repeat(120)
        assertEquals(emoji, familyRequest(draft(title = emoji)).title)
    }

    @Test
    fun `a title with a line break is refused`() {
        assertEquals(EventProblem.TITLE_NOT_ONE_LINE, problem(draft(title = "Dentist\nthen shops")))
    }

    @Test
    fun `an end at or before the start is refused`() {
        assertEquals(EventProblem.END_NOT_AFTER_START, problem(draft(end = LocalTime.of(15, 0))))
        assertEquals(EventProblem.END_NOT_AFTER_START, problem(draft(end = LocalTime.of(14, 0))))
    }

    @Test
    fun `an all-day event doesn't care about the times`() {
        assertEquals("2026-11-25", familyRequest(draft(allDay = true, end = LocalTime.of(9, 0))).date)
    }

    @Test
    fun `a time the clocks skip in spring is refused`() {
        val springForward = LocalDate.of(2027, 3, 28)
        assertEquals(
            EventProblem.TIME_SKIPPED,
            problem(draft(date = springForward, start = LocalTime.of(1, 30), end = LocalTime.of(3, 0)))
        )
    }

    @Test
    fun `a time the autumn change repeats is sent as typed`() {
        val autumnBack = LocalDate.of(2026, 10, 25)
        assertEquals(
            "2026-10-25T01:30",
            familyRequest(draft(date = autumnBack, start = LocalTime.of(1, 30), end = LocalTime.of(2, 30))).start
        )
    }

    // --- my calendar ---

    @Test
    fun `a My calendar event keeps a typed prefix and has no length limit`() {
        val mine = NewCalendarEvent.validate(
            draft(title = "Jess: " + "a".repeat(200), calendar = EventCalendar.MINE),
            london
        ) as EventValidation.Mine
        assertEquals("Jess: " + "a".repeat(200), mine.event.title)
    }

    @Test
    fun `a timed My calendar event is in the phone's zone`() {
        val event = (NewCalendarEvent.validate(draft(calendar = EventCalendar.MINE), london) as EventValidation.Mine).event
        assertEquals(ZonedDateTime.of(day, LocalTime.of(15, 0), london).toInstant().toEpochMilli(), event.startMillis)
        assertEquals(ZonedDateTime.of(day, LocalTime.of(15, 45), london).toInstant().toEpochMilli(), event.endMillis)
        assertEquals(false, event.allDay)
        assertEquals("Europe/London", event.timeZone)
    }

    @Test
    fun `an all-day My calendar event is UTC midnight to the next, whatever the phone's zone`() {
        val event = (NewCalendarEvent.validate(
            draft(allDay = true, calendar = EventCalendar.MINE),
            ZoneId.of("America/New_York")
        ) as EventValidation.Mine).event
        val utc = ZoneId.of("UTC")
        assertEquals(day.atStartOfDay(utc).toInstant().toEpochMilli(), event.startMillis)
        assertEquals(day.plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli(), event.endMillis)
        assertEquals(true, event.allDay)
        assertEquals("UTC", event.timeZone)
    }

    @Test
    fun `My calendar refuses a blank title and an end before the start too`() {
        assertEquals(EventProblem.BLANK_TITLE, problem(draft(title = "", calendar = EventCalendar.MINE)))
        assertEquals(
            EventProblem.END_NOT_AFTER_START,
            problem(draft(calendar = EventCalendar.MINE, end = LocalTime.of(14, 0)))
        )
    }

    // --- who the picker starts on ---

    @Test
    fun `the Who picker starts on the profile's own name`() {
        assertEquals(EventWho.NICK, EventWho.defaultFor(Profile.NICK))
        assertEquals(EventWho.JESS, EventWho.defaultFor(Profile.JESS))
        assertEquals(EventWho.FAMILY, EventWho.defaultFor(null))
    }
}
