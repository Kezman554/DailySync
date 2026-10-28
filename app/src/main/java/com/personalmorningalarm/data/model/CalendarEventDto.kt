package com.personalmorningalarm.data.model

import com.google.gson.annotations.SerializedName

/**
 * Alfred's /calendar/events response: the household diary (family Google
 * Calendar) for a date range, read through Home Assistant.
 *
 * Read-only — creating and updating events is a later card.
 */
data class CalendarEventsDto(
    val calendar: String?,
    val events: List<CalendarEventDto>?
)

/**
 * One family-calendar event, exactly as it comes off the wire (so everything is
 * nullable).
 *
 * [start] and [end] carry a plain ISO date for an all-day event and an ISO
 * datetime for a timed one. For all-day events [end] follows Google's convention
 * and is EXCLUSIVE — a single day on the 24th ends "2026-07-25" — which is why
 * nothing here spans a day range without going through [FamilyCalendar].
 */
data class CalendarEventDto(
    val summary: String?,
    val start: String?,
    val end: String?,
    @SerializedName("all_day") val allDay: Boolean?,
    val location: String?,
    val description: String?
)
