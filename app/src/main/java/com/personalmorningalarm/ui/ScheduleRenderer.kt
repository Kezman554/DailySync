package com.personalmorningalarm.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.AgendaEntry
import com.personalmorningalarm.data.model.AgendaGroup
import com.personalmorningalarm.data.model.DayAgenda
import com.personalmorningalarm.data.model.ScheduleGroup
import com.personalmorningalarm.data.model.SchedulePeriod
import com.personalmorningalarm.util.VaultText
import java.time.format.DateTimeFormatter

/**
 * Renders a day as a bold period heading followed by that period's rows: the
 * vault's schedule blocks and the family calendar's events, merged.
 *
 * Shared by every screen that shows the schedule — the Stage 2 content screen, the
 * Today screen and the week pager — so the day can't drift into looking like
 * several different things depending on where you meet it.
 *
 * Family-calendar rows are marked with a coloured band and their start time, and
 * carry no controls anywhere: the calendar is read-only in this card.
 */
object ScheduleRenderer {

    /** The colour band that marks a row as coming from the family calendar. */
    private const val BAND = "▌ "

    /** Vault schedule blocks. */
    private const val BULLET = "• "

    /** Schedule with no calendar layered in — the vault plan on its own. */
    fun format(context: Context, groups: List<ScheduleGroup>): CharSequence {
        val agenda = DayAgenda(
            allDay = emptyList(),
            groups = groups.map { group ->
                AgendaGroup(group.period, group.tasks.map { AgendaEntry(it, null, fromCalendar = false) })
            }
        )
        // No calendar rows to mark, so the colour is never read.
        return format(context, agenda, calendarColor = 0)
    }

    /**
     * The merged day. [calendarColor] marks the family-calendar rows; callers pass
     * the theme accent (?attr/colorPrimary) resolved against their own view.
     */
    fun format(context: Context, agenda: DayAgenda, @ColorInt calendarColor: Int): CharSequence {
        val out = SpannableStringBuilder()

        // All-day events belong to no time of day, so they head the day as markers.
        agenda.allDay.forEach { event ->
            if (out.isNotEmpty()) out.append("\n")
            val start = out.length
            out.append(BAND).append(event.summary)
            out.setSpan(ForegroundColorSpan(calendarColor), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        agenda.groups.forEach { group ->
            if (out.isNotEmpty()) out.append("\n\n")
            val headingStart = out.length
            out.append(context.getString(periodHeading(group.period)))
            out.setSpan(
                StyleSpan(Typeface.BOLD),
                headingStart,
                out.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            group.entries.forEach { entry ->
                out.append("\n")
                val entryStart = out.length
                if (entry.fromCalendar) {
                    out.append(BAND)
                    entry.time?.let { out.append(it.format(TIME_FORMAT)).append("  ") }
                    // Calendar summaries come from Google, not the vault — no
                    // wikilinks or markdown to resolve, so they render as sent.
                    out.append(entry.text)
                    out.setSpan(
                        ForegroundColorSpan(calendarColor),
                        entryStart,
                        out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else {
                    // Build the bullet around the rendered task so its emphasis
                    // spans survive — getString would flatten them to plain text.
                    out.append(BULLET).append(VaultText.render(entry.text))
                }
            }
        }
        return out
    }

    @StringRes
    fun periodHeading(period: SchedulePeriod): Int = when (period) {
        SchedulePeriod.EARLY -> R.string.schedule_period_early
        SchedulePeriod.MORNING -> R.string.schedule_period_morning
        SchedulePeriod.AFTERNOON -> R.string.schedule_period_afternoon
        SchedulePeriod.EVENING -> R.string.schedule_period_evening
        SchedulePeriod.UNGROUPED -> R.string.schedule_period_ungrouped
    }

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
