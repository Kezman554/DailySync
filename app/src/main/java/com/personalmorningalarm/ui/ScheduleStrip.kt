package com.personalmorningalarm.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.view.updateLayoutParams
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.personalmorningalarm.R
import com.personalmorningalarm.databinding.ItemScheduleColumnBinding
import com.personalmorningalarm.databinding.ItemScheduleLineBinding
import com.personalmorningalarm.util.VaultText

/**
 * Today's period strip — one column per period with something in it, the current
 * period in accent — as Home's Daily Schedule card and the alarm's schedule screen
 * both show it. [strip] sits inside [scroll], which scrolls sideways when the
 * columns can't each have their minimum width.
 *
 * Every entry is one ellipsised line, so the strip's size never depends on how
 * long a title is: a summary says which things are on, and the opened schedule
 * carries the whole title.
 */
object ScheduleStrip {

    /** Tasks shown per column before "+N more". */
    const val PREVIEW = 3

    /**
     * What an entry's line says. Vault text goes through [VaultText] so its markup
     * never shows; a calendar title has no authoring syntax, so it stays as sent —
     * a real asterisk in it is part of the title.
     */
    fun entryText(entry: HomeTiles.ScheduleEntry): CharSequence =
        if (entry.fromCalendar) entry.text else VaultText.render(entry.text)

    fun render(strip: LinearLayout, scroll: HorizontalScrollView, columns: List<HomeTiles.ScheduleColumn>) {
        val context = strip.context
        val inflater = LayoutInflater.from(context)
        fun color(attr: Int) = MaterialColors.getColor(strip, attr)

        strip.removeAllViews()
        columns.forEachIndexed { index, column ->
            val item = ItemScheduleColumnBinding.inflate(inflater, strip, true)
            if (index < columns.lastIndex) {
                item.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    marginEnd = context.resources.getDimensionPixelSize(R.dimen.schedule_column_gap)
                }
            }
            item.bar.backgroundTintList = ColorStateList.valueOf(
                color(if (column.current) MaterialR.attr.colorPrimary else R.attr.colorSurface2)
            )
            item.tvPeriod.setText(ScheduleRenderer.periodHeading(column.period))
            item.tvPeriod.setTextColor(
                color(if (column.current) MaterialR.attr.colorPrimary else MaterialR.attr.colorOnSurfaceVariant)
            )
            val shown = column.entries.take(PREVIEW)
            val more = column.entries.size - shown.size
            val lines = shown.map(::entryText) +
                listOfNotNull(more.takeIf { it > 0 }?.let { context.getString(R.string.tiles_schedule_more, it) })
            lines.forEachIndexed { i, text ->
                val line = ItemScheduleLineBinding.inflate(inflater, item.tasks, true).root
                if (i > 0) {
                    line.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        topMargin = context.resources.getDimensionPixelSize(R.dimen.schedule_line_gap)
                    }
                }
                line.text = text
            }
        }

        // When the strip scrolls, make sure the current period starts in view.
        val currentIndex = columns.indexOfFirst { it.current }
        scroll.post {
            val current = strip.getChildAt(currentIndex)
            scroll.scrollTo(
                if (current != null && current.right > scroll.width) current.right - scroll.width else 0,
                0
            )
        }
    }
}
