package com.personalmorningalarm.ui

import android.view.LayoutInflater
import android.widget.LinearLayout
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.FamilyEvent
import com.personalmorningalarm.databinding.ItemWeekPeriodRowBinding
import java.time.format.DateTimeFormatter

/**
 * Family-calendar events as rows: the start time (or "All day") in the Week
 * screen's label column, the title beside it. Shared by the Today card and the
 * Calendar screen, so a day reads the same in both. Events arrive already ordered
 * (all day first, then by start: [HomeTiles.today], [HomeTiles.agenda]).
 *
 * Titles take the ordinary text colour: on these screens everything is from the
 * calendar, so the Week screen's accent, which tells calendar from plan, has
 * nothing to tell apart.
 */
object EventRows {

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun render(rows: LinearLayout, events: List<FamilyEvent>) {
        rows.removeAllViews()
        val inflater = LayoutInflater.from(rows.context)
        events.forEach { event ->
            val row = ItemWeekPeriodRowBinding.inflate(inflater, rows, true)
            row.tvPeriod.text = event.startTime?.format(TIME) ?: rows.context.getString(R.string.week_all_day)
            row.tvTasks.text = event.summary
        }
    }
}
