package com.personalmorningalarm.data.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * One family-calendar event as the screens show it: already resolved to a single
 * day, so a multi-day event appears once per day it covers.
 *
 * [startTime] is null exactly when [allDay] is true — an all-day event renders as
 * a marker with no time.
 */
data class FamilyEvent(
    val summary: String,
    val date: LocalDate,
    val startTime: LocalTime?,
    val allDay: Boolean
)

/** One row of a merged day: either a vault schedule block or a calendar event. */
data class AgendaEntry(
    val text: String,
    val time: LocalTime?,
    val fromCalendar: Boolean
)

/** A period's rows once the vault plan and the family calendar are merged. */
data class AgendaGroup(
    val period: SchedulePeriod,
    val entries: List<AgendaEntry>
)

/**
 * A whole day as the screens render it: all-day markers first (they belong to no
 * time of day), then the period groups.
 */
data class DayAgenda(
    val allDay: List<FamilyEvent>,
    val groups: List<AgendaGroup>
) {
    val isEmpty: Boolean get() = allDay.isEmpty() && groups.isEmpty()
}

/**
 * The one place the family calendar is turned into something renderable, shared by
 * the Daily Schedule tile and the alarm's wake-up schedule page so the family's day
 * can't come to look like two different things depending on where you meet it.
 *
 * Nothing here throws: a malformed event is dropped, and a malformed response is an
 * empty calendar. The wake page must render with the calendar absent, so "absent"
 * is the failure mode everywhere.
 */
object FamilyCalendar {

    /**
     * Every event bucketed by the day it falls on, ready for a week's worth of
     * pages to be rendered from one fetch. Within a day: all-day markers first,
     * then timed events in start order.
     */
    fun byDate(dto: CalendarEventsDto?): Map<LocalDate, List<FamilyEvent>> {
        val byDate = mutableMapOf<LocalDate, MutableList<FamilyEvent>>()
        dto?.events.orEmpty().forEach { event ->
            // An event with no title is nothing to display, whatever its dates.
            val summary = event.summary?.trim().orEmpty()
            if (summary.isEmpty()) return@forEach

            val startTime = timedStart(event)
            if (startTime == null) {
                // All-day: one marker per day it covers, honouring the exclusive end.
                allDayDates(event).forEach { date ->
                    byDate.getOrPut(date) { mutableListOf() }
                        .add(FamilyEvent(summary, date, null, allDay = true))
                }
            } else {
                val date = startTime.toLocalDate()
                byDate.getOrPut(date) { mutableListOf() }
                    .add(FamilyEvent(summary, date, startTime.toLocalTime(), allDay = false))
            }
        }
        return byDate.mapValues { (_, events) ->
            events.sortedWith(compareBy({ !it.allDay }, { it.startTime }))
        }
    }

    /**
     * Merges a day's vault schedule blocks with that day's family-calendar events.
     *
     * Vault blocks carry a period ("early"/"am"/"pm"/"eve"/none), never a clock time, so a timed
     * event is placed in the period its start time falls in and sorts by time
     * against the other events there; the untimed vault blocks keep Alfred's order
     * beneath them. All-day events belong to no period and come back separately as
     * markers for the top of the day.
     */
    fun agenda(tasks: List<ScheduleTaskDto>, events: List<FamilyEvent>): DayAgenda {
        val vaultGroups = DailySchedule.group(tasks)
        val timedByPeriod = events
            .filter { !it.allDay && it.startTime != null }
            .sortedBy { it.startTime }
            .groupBy { periodOf(it.startTime!!) }

        // Driven off the enum so period order is display order, exactly as the
        // vault-only grouping does.
        val groups = SchedulePeriod.entries.mapNotNull { period ->
            val calendarEntries = timedByPeriod[period].orEmpty()
                .map { AgendaEntry(it.summary, it.startTime, fromCalendar = true) }
            val vaultEntries = vaultGroups.firstOrNull { it.period == period }?.tasks.orEmpty()
                .map { AgendaEntry(it, null, fromCalendar = false) }
            (calendarEntries + vaultEntries)
                .takeIf { it.isNotEmpty() }
                ?.let { AgendaGroup(period, it) }
        }
        return DayAgenda(allDay = events.filter { it.allDay }, groups = groups)
    }

    /**
     * Which part of the day a timed event belongs under. The boundaries are the
     * ones the plan writer and vault-api use (AlfredHomeHub
     * docs/schedule-period-contract.md), so a 17:30 event sits beside the vault's
     * Evening items — and in the same group the kitchen wall puts it in.
     */
    internal fun periodOf(time: LocalTime): SchedulePeriod = when {
        time < MORNING_FROM -> SchedulePeriod.EARLY
        time < LocalTime.NOON -> SchedulePeriod.MORNING
        time < EVENING_FROM -> SchedulePeriod.AFTERNOON
        else -> SchedulePeriod.EVENING
    }

    /**
     * The event's start as a datetime, or null if this is an all-day event.
     *
     * `all_day: true` is the switch, per the endpoint's contract. A date-only start
     * is treated as all-day even without the flag: rendering it as "00:00" would be
     * a worse guess than the one the shape already tells us.
     */
    private fun timedStart(event: CalendarEventDto): LocalDateTime? {
        if (event.allDay == true) return null
        return parseDateTime(event.start)
    }

    /** The days an all-day event covers, from an inclusive start to an exclusive end. */
    private fun allDayDates(event: CalendarEventDto): List<LocalDate> {
        val start = parseDate(event.start) ?: return emptyList()
        // Google's convention: `end` is EXCLUSIVE, so a one-day event on the 24th
        // ends "2026-07-25" and must show on the 24th only.
        val last = parseDate(event.end)?.minusDays(1)
        if (last == null || last.isBefore(start)) return listOf(start)
        // A span this long is a malformed range, not a holiday — show the start day
        // rather than papering hundreds of pages with it.
        if (ChronoUnit.DAYS.between(start, last) > MAX_EVENT_DAYS) return listOf(start)
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(last) }
            .toList()
    }

    private fun parseDate(value: String?): LocalDate? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return try {
            LocalDate.parse(text)
        } catch (e: Exception) {
            // A datetime where a date was expected still names a day.
            parseDateTime(text)?.toLocalDate()
        }
    }

    private fun parseDateTime(value: String?): LocalDateTime? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return try {
            // Timed events carry an offset ("…+01:00"); keep the wall-clock time.
            OffsetDateTime.parse(text).toLocalDateTime()
        } catch (e: Exception) {
            try {
                LocalDateTime.parse(text)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Before this is Early. */
    private val MORNING_FROM: LocalTime = LocalTime.of(9, 0)

    /** Evening starts here — 17:00, the API's boundary, not ours to move. */
    private val EVENING_FROM: LocalTime = LocalTime.of(17, 0)

    /** Longer than this and the range is malformed, not a real event. */
    private const val MAX_EVENT_DAYS = 60L
}
