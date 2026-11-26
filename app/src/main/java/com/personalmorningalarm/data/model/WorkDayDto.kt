package com.personalmorningalarm.data.model

import com.google.gson.annotations.SerializedName

/**
 * GET /daily-schedule/work-day: whether [date] (the hub's today) is a work day.
 * [source] is "plan" when today's heading in the week plan said so, "fallback"
 * when the hub fell back to Monday to Friday. Every field is nullable: Gson fills
 * what the hub sent and nothing else.
 */
data class WorkDayDto(
    val date: String? = null,
    @SerializedName("work_day") val workDay: Boolean? = null,
    val source: String? = null
)
