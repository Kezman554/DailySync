package com.personalmorningalarm.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.FamilyCalendar
import com.personalmorningalarm.data.model.FamilyEvent
import com.personalmorningalarm.data.model.WeekDay
import com.personalmorningalarm.databinding.ItemWeekDayBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The week pager's pages, one per plan day. Past days render muted but still
 * legible — the use case is glancing back at what yesterday held to carry it
 * forward, so they must stay readable, never disabled.
 *
 * The day list is set once per fetch; swiping only binds pages, it never loads.
 */
class WeekDayAdapter : RecyclerView.Adapter<WeekDayAdapter.DayViewHolder>() {

    private var days: List<WeekDay> = emptyList()
    private var today: LocalDate = LocalDate.now()

    /** The family calendar, bucketed by day. Empty until (or unless) it arrives. */
    private var eventsByDate: Map<LocalDate, List<FamilyEvent>> = emptyMap()

    fun setDays(days: List<WeekDay>, today: LocalDate) {
        this.days = days
        this.today = today
        notifyDataSetChanged()
    }

    /** Layers the family calendar over the days already showing. */
    fun setEvents(eventsByDate: Map<LocalDate, List<FamilyEvent>>) {
        this.eventsByDate = eventsByDate
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder =
        DayViewHolder(
            ItemWeekDayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun getItemCount(): Int = days.size

    override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
        val day = days[position]
        holder.bind(day, today, eventsByDate[day.date].orEmpty())
    }

    class DayViewHolder(private val binding: ItemWeekDayBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(day: WeekDay, today: LocalDate, events: List<FamilyEvent>) {
            val context = binding.root.context
            binding.tvDayHeader.text = day.date.format(HEADER_FORMAT)
            binding.tvDayToday.isVisible = day.date == today

            val agenda = FamilyCalendar.agenda(day.tasks, events)
            binding.tvDayBody.text = if (agenda.isEmpty) {
                context.getString(R.string.week_day_empty)
            } else {
                val calendarColor = MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorPrimary
                )
                ScheduleRenderer.format(context, agenda, calendarColor)
            }

            // Muted, not disabled: yesterday's plan is exactly what the screen
            // exists to glance back at.
            binding.root.alpha = if (day.date.isBefore(today)) PAST_DAY_ALPHA else 1f
        }

        private companion object {
            val HEADER_FORMAT: DateTimeFormatter =
                DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())
            const val PAST_DAY_ALPHA = 0.55f
        }
    }
}
