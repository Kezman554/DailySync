package com.personalmorningalarm.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import androidx.annotation.ColorInt
import com.personalmorningalarm.data.model.RollingTodoItem
import com.personalmorningalarm.util.VaultText

/**
 * Renders the rolling to-do as a read-only checklist for the Stage 2 alarm
 * content screen: a box per item, with the item's date on a faint second line
 * when it has one. Items ticked elsewhere but not yet swept by the Pi's
 * overnight housekeeping show as done — a ticked box, struck through — because
 * a wake-up reminder that resurrects finished tasks misinforms. Read-only stays
 * read-only: nothing here ticks anything off. (The Today screen edits via
 * [RollingTodoAdapter], not this.)
 */
object ChalkboardRenderer {

    private const val BOX = "☐  "
    private const val BOX_DONE = "☑  "

    /** Indents the date under its task rather than under the box. */
    private const val DATE_INDENT = "     "

    fun format(context: Context, items: List<RollingTodoItem>, @ColorInt dateColor: Int): CharSequence {
        val out = SpannableStringBuilder()
        items.forEach { item ->
            if (out.isNotEmpty()) out.append("\n")
            // Build the line around the rendered task so its emphasis spans survive.
            val start = out.length
            out.append(if (item.done) BOX_DONE else BOX).append(VaultText.render(context, item.task))
            if (item.done) {
                out.setSpan(StrikethroughSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(dateColor), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            item.date?.let { date ->
                out.append("\n")
                val dateStart = out.length
                out.append(DATE_INDENT).append(date)
                // Secondary — present, but not competing with the task itself.
                out.setSpan(RelativeSizeSpan(0.8f), dateStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(dateColor), dateStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }
}
