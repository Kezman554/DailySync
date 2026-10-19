package com.personalmorningalarm.ui

import androidx.annotation.StringRes
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.SchedulePeriod

/**
 * The names the schedule's periods go by, shared by every screen that shows the
 * day — the Home strip, the alarm's schedule screen and the week pager — so a
 * period can't be called one thing in one place and another elsewhere.
 */
object ScheduleRenderer {

    @StringRes
    fun periodHeading(period: SchedulePeriod): Int = when (period) {
        SchedulePeriod.EARLY -> R.string.schedule_period_early
        SchedulePeriod.MORNING -> R.string.schedule_period_morning
        SchedulePeriod.AFTERNOON -> R.string.schedule_period_afternoon
        SchedulePeriod.EVENING -> R.string.schedule_period_evening
        SchedulePeriod.UNGROUPED -> R.string.schedule_period_ungrouped
    }
}
