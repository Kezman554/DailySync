package com.personalmorningalarm.ui

import com.personalmorningalarm.data.entity.AlarmConfig
import com.personalmorningalarm.data.model.CalendarEventsDto
import com.personalmorningalarm.data.model.DayAgenda
import com.personalmorningalarm.data.model.FamilyCalendar
import com.personalmorningalarm.data.model.FamilyEvent
import com.personalmorningalarm.data.model.SchedulePeriod
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.remote.AlfredResult
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * What the Home tiles show, worked out from the raw data with no Android in sight
 * so it can be tested on the JVM. TilesFragment and the header only render it.
 */
object HomeTiles {

    /** The next time the alarm fires: [time], today or [tomorrow]. */
    data class NextAlarm(val time: LocalTime, val tomorrow: Boolean)

    /**
     * The next alarm, or null when it's off. The alarm fires daily at
     * [AlarmConfig.alarmTime], so the next one is today if that time is still
     * ahead of [now], otherwise tomorrow — the same rule AlarmScheduler arms by.
     */
    fun nextAlarm(config: AlarmConfig?, now: LocalDateTime): NextAlarm? {
        if (config == null || !config.isEnabled) return null
        val time = LocalTime.of(config.alarmTime / 60, config.alarmTime % 60)
        return NextAlarm(time, tomorrow = !time.isAfter(now.toLocalTime()))
    }

    /**
     * One line of a strip column: its text (a calendar event's start time already
     * in front) and where it came from. Vault text still carries authoring markup
     * for the strip to resolve; calendar text comes from Google and shows as sent.
     */
    data class ScheduleEntry(val text: String, val fromCalendar: Boolean)

    /** One column of the Daily Schedule strip. */
    data class ScheduleColumn(
        val period: SchedulePeriod,
        val entries: List<ScheduleEntry>,
        /** The part of the day [now] falls in — drawn in accent. */
        val current: Boolean
    )

    /**
     * One column per period that has something today, in [SchedulePeriod] order
     * (Early, Morning, Afternoon, Evening, then Anytime last). Empty periods are
     * already absent from [DayAgenda.groups]. Anytime has no clock range, so it is
     * never the current column. All-day calendar events belong to no period and
     * are not part of the strip.
     */
    fun scheduleColumns(agenda: DayAgenda, now: LocalTime): List<ScheduleColumn> {
        val currentPeriod = FamilyCalendar.periodOf(now)
        return agenda.groups.map { group ->
            ScheduleColumn(
                period = group.period,
                entries = group.entries.map { entry ->
                    ScheduleEntry(
                        text = entry.time?.let { "${it.format(TIME)} ${entry.text}" } ?: entry.text,
                        fromCalendar = entry.fromCalendar
                    )
                },
                current = group.period == currentPeriod
            )
        }
    }

    /**
     * The first list with anything left on it, the hub's lists as Alfred orders them
     * and then the phone's [phoneLists]; null if none.
     */
    fun activeShoppingList(
        lists: List<ShoppingListSummaryDto>,
        phoneLists: List<ShoppingListSummaryDto> = emptyList()
    ): ShoppingListSummaryDto? =
        (lists + phoneLists).firstOrNull { (it.unticked ?: 0) > 0 && !it.title.isNullOrBlank() }

    /** What the Today card shows. */
    sealed interface Today {
        /** Not asked yet. */
        data object Loading : Today

        /** No copy of today's calendar on the phone, and the hub can't be reached. */
        data object Unreachable : Today

        /**
         * Today's family events: all-day ones first (they describe the day), then
         * timed ones in start order. Empty is a day with nothing on.
         */
        data class Events(val events: List<FamilyEvent>) : Today
    }

    /**
     * The Today card from the day's calendar fetch. A saved copy counts only if it
     * was saved [today]: the day's calendar is cached under one key whatever the
     * date, so yesterday's copy says nothing about today, and offline it would read
     * as "nothing on". That's unreachable, said plainly.
     */
    fun today(
        result: AlfredResult<CalendarEventsDto>?,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): Today {
        val data = when (result) {
            null -> return Today.Loading
            AlfredResult.Unavailable -> return Today.Unreachable
            is AlfredResult.Fresh -> result.data
            is AlfredResult.Stale -> {
                val savedOn = Instant.ofEpochMilli(result.cachedAtMillis).atZone(zone).toLocalDate()
                if (savedOn != today) return Today.Unreachable
                result.data
            }
        }
        return Today.Events(FamilyCalendar.byDate(data)[today].orEmpty())
    }

    /**
     * The Calendar screen's agenda: each day from [from] for [days] days that has
     * anything on, in date order, each day's events ordered as [today] orders them.
     * Days before [from] are dropped, so an older saved copy shows only what's ahead.
     */
    fun agenda(dto: CalendarEventsDto?, from: LocalDate, days: Long): List<Pair<LocalDate, List<FamilyEvent>>> {
        val until = from.plusDays(days)
        return FamilyCalendar.byDate(dto)
            .filterKeys { !it.isBefore(from) && it.isBefore(until) }
            .toSortedMap()
            .map { (date, events) -> date to events }
    }

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
