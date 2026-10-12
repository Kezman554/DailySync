package com.personalmorningalarm.data.model

/** Today's tasks for one period, in the order Alfred sent them. */
data class ScheduleGroup(
    val period: SchedulePeriod,
    val tasks: List<String>
)

/**
 * Turns Alfred's flat task array into the grouped form the screen renders:
 * Early, Morning, Afternoon, Evening, then ungrouped. Empty groups are dropped so the
 * screen never shows a heading with nothing under it.
 */
object DailySchedule {

    fun group(tasks: List<ScheduleTaskDto>): List<ScheduleGroup> {
        val byPeriod = tasks
            // A task with no text is nothing to display, whatever its period.
            .filter { !it.task.isNullOrBlank() }
            .groupBy({ SchedulePeriod.fromApiValue(it.period) }, { it.task!!.trim() })

        // Drive off the enum, not the response, so group order is the display
        // order regardless of what order Alfred sent the tasks in. Within a
        // group, groupBy preserves the response order.
        return SchedulePeriod.entries.mapNotNull { period ->
            byPeriod[period]?.takeIf { it.isNotEmpty() }?.let { ScheduleGroup(period, it) }
        }
    }
}
