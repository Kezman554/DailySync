package com.personalmorningalarm.ui

import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.text.inSpans
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.FamilyCalendar
import com.personalmorningalarm.data.model.FamilyEvent
import com.personalmorningalarm.data.model.WeekDay
import com.personalmorningalarm.databinding.ItemWeekDayBinding
import com.personalmorningalarm.databinding.ItemWeekPeriodRowBinding
import com.personalmorningalarm.util.VaultText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The week pager's pages, one per plan day: TODAY or the weekday, the date, and a
 * card with one row per period (all-day calendar markers first). Past days render
 * muted but still legible — the use case is glancing back at what yesterday held
 * to carry it forward, so they must stay readable, never disabled.
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

    /** The day on page [position], for the pills and the footer. */
    fun dayAt(position: Int): WeekDay? = days.getOrNull(position)

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
            binding.tvDayLabel.text = if (day.date == today) {
                context.getString(R.string.week_day_today)
            } else {
                day.date.format(WEEKDAY_FORMAT)
            }
            binding.tvDayHeader.text = day.date.format(HEADER_FORMAT)

            val rows = binding.dayRows
            rows.removeAllViews()
            val agenda = FamilyCalendar.agenda(day.tasks, events)
            val inflater = LayoutInflater.from(context)
            fun addRow(label: CharSequence, tasks: CharSequence) {
                val row = ItemWeekPeriodRowBinding.inflate(inflater, rows, true)
                row.tvPeriod.text = label
                row.tvTasks.text = tasks
            }

            if (agenda.isEmpty) {
                addRow("", context.getString(R.string.week_day_empty))
            } else {
                val calendarColor = MaterialColors.getColor(
                    binding.root, com.google.android.material.R.attr.colorPrimary
                )
                // All-day markers belong to no period; they describe the day.
                if (agenda.allDay.isNotEmpty()) {
                    addRow(
                        context.getString(R.string.week_all_day),
                        SpannableStringBuilder().inSpans(ForegroundColorSpan(calendarColor)) {
                            append(agenda.allDay.joinToString("\n") { it.summary })
                        }
                    )
                }
                agenda.groups.forEach { group ->
                    val tasks = SpannableStringBuilder()
                    group.entries.forEachIndexed { i, entry ->
                        if (i > 0) tasks.append("\n")
                        if (entry.fromCalendar) {
                            // Calendar events carry their time and the accent, as on Home.
                            tasks.inSpans(ForegroundColorSpan(calendarColor)) {
                                append(entry.time?.format(TIME_FORMAT)?.let { "$it ${entry.text}" } ?: entry.text)
                            }
                        } else {
                            // The vault's own markup (bold, wikilinks) rendered, as on Home.
                            tasks.append(VaultText.render(entry.text))
                        }
                    }
                    addRow(context.getString(ScheduleRenderer.periodHeading(group.period)), tasks)
                }
            }

            // Muted, not disabled: yesterday's plan is exactly what the screen
            // exists to glance back at.
            binding.dayPage.alpha = if (day.date.isBefore(today)) PAST_DAY_ALPHA else 1f
        }

        private companion object {
            val HEADER_FORMAT: DateTimeFormatter =
                DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault())
            val WEEKDAY_FORMAT: DateTimeFormatter =
                DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
            val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
            const val PAST_DAY_ALPHA = 0.55f
        }
    }
}
