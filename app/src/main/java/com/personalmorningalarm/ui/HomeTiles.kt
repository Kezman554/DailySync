package com.personalmorningalarm.ui

import com.personalmorningalarm.data.entity.AlarmConfig
import com.personalmorningalarm.data.model.DayAgenda
import com.personalmorningalarm.data.model.FamilyCalendar
import com.personalmorningalarm.data.model.SchedulePeriod
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import java.time.LocalDateTime
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

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
