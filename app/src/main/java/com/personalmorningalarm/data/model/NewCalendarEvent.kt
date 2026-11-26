package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.profile.Profile
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Which calendar a new event goes in: the family's, through the hub, or one on this phone. */
enum class EventCalendar {
    /** Young Family, by POST /calendar/events. Needs the hub; never queued, never retried. */
    FAMILY,

    /** A Google calendar already synced on this phone, through CalendarContract. Never touches the Pi. */
    MINE
}

/**
 * Who a Young Family event is for: vault-api's `who` allowlist. The server stores the
 * title as "Jess: dentist" for a person and bare for the family, so [prefixName] is
 * only ever used here to take a typed prefix *off*.
 */
enum class EventWho(val wireName: String, val prefixName: String?) {
    NICK("nick", "Nick"),
    JESS("jess", "Jess"),
    OLIVER("oliver", "Oliver"),
    FAMILY("family", null);

    companion object {
        /** The picker starts on the phone's own person; with no profile yet, the family. */
        fun defaultFor(profile: Profile?): EventWho = when (profile) {
            Profile.NICK -> NICK
            Profile.JESS -> JESS
            null -> FAMILY
        }
    }
}

/** Everything typed on the Add Event screen, exactly as typed. */
data class EventDraft(
    val title: String,
    val date: LocalDate,
    val allDay: Boolean,
    val start: LocalTime,
    val end: LocalTime,
    val calendar: EventCalendar,
    val who: EventWho
)

/**
 * Body for POST /calendar/events: the bare title, who, and either [date] (all day)
 * or [start] and [end] (Europe/London wall-clock times with no offset). Gson leaves
 * the null pair out.
 */
data class CalendarCreateRequest(
    val title: String,
    val who: String,
    val date: String? = null,
    val start: String? = null,
    val end: String? = null
)

/** An event for this phone's calendar, as CalendarContract stores it. */
data class PhoneEvent(
    val title: String,
    /** Epoch millis. For an all-day event, UTC midnight, as CalendarContract requires. */
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timeZone: String
)

/** Why a draft can't be saved yet. */
enum class EventProblem {
    BLANK_TITLE,
    TITLE_TOO_LONG,
    TITLE_NOT_ONE_LINE,
    END_NOT_AFTER_START,
    /** A time the clocks skip in spring: vault-api refuses it, so it's caught here. */
    TIME_SKIPPED
}

/** A draft checked and turned into the one request its calendar takes. */
sealed interface EventValidation {
    data class Family(val request: CalendarCreateRequest) : EventValidation
    data class Mine(val event: PhoneEvent) : EventValidation
    data class Invalid(val problem: EventProblem) : EventValidation
}

/**
 * The Add Event screen's rules, kept off the screen so they can be tested. Mirrors
 * vault-api's own checks for a family event (homeassistant.py event_title and
 * plan_event) so a refusal is said before anything is sent.
 */
object NewCalendarEvent {

    /** vault-api's CALENDAR_TITLE_MAX, counted in characters before the prefix. */
    const val TITLE_MAX = 120

    /** The family calendar's zone: vault-api sends times to HA in it, with no offset. */
    val FAMILY_ZONE: ZoneId = ZoneId.of("Europe/London")

    private val WALL_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    fun validate(draft: EventDraft, phoneZone: ZoneId = ZoneId.systemDefault()): EventValidation {
        val title = when (draft.calendar) {
            EventCalendar.FAMILY -> stripPrefix(draft.title, draft.who)
            EventCalendar.MINE -> draft.title.trim()
        }
        if (title.isEmpty()) return EventValidation.Invalid(EventProblem.BLANK_TITLE)
        if (title.any { Character.getType(it) == Character.CONTROL.toInt() }) {
            return EventValidation.Invalid(EventProblem.TITLE_NOT_ONE_LINE)
        }
        if (draft.calendar == EventCalendar.FAMILY && title.codePointCount(0, title.length) > TITLE_MAX) {
            return EventValidation.Invalid(EventProblem.TITLE_TOO_LONG)
        }
        if (!draft.allDay && !draft.end.isAfter(draft.start)) {
            return EventValidation.Invalid(EventProblem.END_NOT_AFTER_START)
        }
        return when (draft.calendar) {
            EventCalendar.FAMILY -> family(draft, title)
            EventCalendar.MINE -> EventValidation.Mine(phoneEvent(draft, title, phoneZone))
        }
    }

    private fun family(draft: EventDraft, title: String): EventValidation {
        if (draft.allDay) {
            return EventValidation.Family(CalendarCreateRequest(title, draft.who.wireName, date = draft.date.toString()))
        }
        val start = draft.date.atTime(draft.start)
        val end = draft.date.atTime(draft.end)
        if (skipped(start) || skipped(end)) return EventValidation.Invalid(EventProblem.TIME_SKIPPED)
        return EventValidation.Family(
            CalendarCreateRequest(
                title,
                draft.who.wireName,
                start = start.format(WALL_CLOCK),
                end = end.format(WALL_CLOCK)
            )
        )
    }

    /** Whether the clocks jump over [time] in the family's zone. */
    private fun skipped(time: LocalDateTime): Boolean =
        FAMILY_ZONE.rules.getValidOffsets(time).isEmpty()

    /**
     * An all-day event is stored as UTC midnight to the next UTC midnight in UTC,
     * whatever the phone's zone (CalendarContract's rule). A timed one is in the
     * phone's zone.
     */
    private fun phoneEvent(draft: EventDraft, title: String, zone: ZoneId): PhoneEvent =
        if (draft.allDay) {
            val utc = ZoneId.of("UTC")
            PhoneEvent(
                title,
                draft.date.atStartOfDay(utc).toInstant().toEpochMilli(),
                draft.date.plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli(),
                allDay = true,
                timeZone = "UTC"
            )
        } else {
            PhoneEvent(
                title,
                draft.date.atTime(draft.start).atZone(zone).toInstant().toEpochMilli(),
                draft.date.atTime(draft.end).atZone(zone).toInstant().toEpochMilli(),
                allDay = false,
                timeZone = zone.id
            )
        }

    /**
     * The title to send: trimmed, with the chosen person's own "Jess:" taken off the
     * front (any case, as often as it was typed), since the server adds it. Another
     * person's prefix is left alone: "Oliver: swimming" for Jess is a title, and
     * family has no prefix to take off.
     */
    fun stripPrefix(title: String, who: EventWho): String {
        var text = title.trim()
        val name = who.prefixName ?: return text
        val prefix = Regex("^${Regex.escape(name)}\\s*:\\s*", RegexOption.IGNORE_CASE)
        while (true) {
            val stripped = prefix.replaceFirst(text, "").trim()
            if (stripped == text) return text
            text = stripped
        }
    }
}
