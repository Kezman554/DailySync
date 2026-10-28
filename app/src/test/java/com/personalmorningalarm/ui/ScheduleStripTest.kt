package com.personalmorningalarm.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.SchedulePeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The schedule strip is a summary: vault markup never shows on it, calendar titles
 * show as Google sent them, and no title is long enough to reshape the tile.
 */
@RunWith(RobolectricTestRunner::class)
// Real text measurement, so a long title genuinely would wrap if the line let it.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleStripTest {

    private val context: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(), R.style.Theme_PersonalMorningAlarm
    )

    @Test
    fun `a vault entry's markup is resolved`() {
        val text = ScheduleStrip.entryText(HomeTiles.ScheduleEntry("Ship **DailySync Y10**", fromCalendar = false))

        assertEquals("Ship DailySync Y10", text.toString())
        val bold = (text as Spanned).getSpans(0, text.length, StyleSpan::class.java).single()
        assertEquals(Typeface.BOLD, bold.style)
        assertEquals("DailySync Y10", text.subSequence(text.getSpanStart(bold), text.getSpanEnd(bold)).toString())
    }

    @Test
    fun `a calendar entry's asterisks are part of its title`() {
        val title = "18:00 Quiz *night* **finals**"
        val text = ScheduleStrip.entryText(HomeTiles.ScheduleEntry(title, fromCalendar = true))

        assertEquals(title, text.toString())
        assertTrue(text !is Spanned || text.getSpans(0, text.length, StyleSpan::class.java).isEmpty())
    }

    @Test
    fun `a long entry stays one line and doesn't resize its column`() {
        val long = "Rework the whole of the garage shelving so the bikes, the mower and the camping kit all fit"
        val (strip, _) = render(
            HomeTiles.ScheduleColumn(SchedulePeriod.MORNING, listOf(entry(long)), current = true),
            HomeTiles.ScheduleColumn(SchedulePeriod.EVENING, listOf(entry("Choir")), current = false)
        )
        val longColumn = strip.getChildAt(0)
        val shortColumn = strip.getChildAt(1)

        val line = lines(longColumn).single()
        assertEquals(1, line.maxLines)
        assertEquals(TextUtils.TruncateAt.END, line.ellipsize)
        assertEquals(1, line.layout.lineCount)
        assertEquals(shortColumn.height, longColumn.height)
        // Equal shares of the strip, give or take the odd pixel the split leaves.
        assertEquals(shortColumn.width.toFloat(), longColumn.width.toFloat(), 1f)
    }

    @Test
    fun `the preview and its more line stay as they were`() {
        val (strip, _) = render(
            HomeTiles.ScheduleColumn(SchedulePeriod.MORNING, (1..5).map { entry("Task $it") }, current = false)
        )
        assertEquals(
            listOf("Task 1", "Task 2", "Task 3", context.getString(R.string.tiles_schedule_more, 2)),
            lines(strip.getChildAt(0)).map { it.text.toString() }
        )
    }

    private fun entry(text: String) = HomeTiles.ScheduleEntry(text, fromCalendar = false)

    /** Renders [columns] into a phone-width scroller, measured and laid out as on screen. */
    private fun render(vararg columns: HomeTiles.ScheduleColumn): Pair<LinearLayout, HorizontalScrollView> {
        val scroll = HorizontalScrollView(context).apply { isFillViewport = true }
        val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        scroll.addView(strip, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        ScheduleStrip.render(strip, scroll, columns.toList())

        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(PHONE_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        scroll.layout(0, 0, scroll.measuredWidth, scroll.measuredHeight)
        return strip to scroll
    }

    private fun lines(column: View): List<TextView> {
        val tasks = column.findViewById<LinearLayout>(R.id.tasks)
        return (0 until tasks.childCount).map { tasks.getChildAt(it) as TextView }
    }

    private companion object {
        // The A32's width in dp; Robolectric runs at mdpi, so dp and px agree.
        const val PHONE_WIDTH = 411
    }
}
