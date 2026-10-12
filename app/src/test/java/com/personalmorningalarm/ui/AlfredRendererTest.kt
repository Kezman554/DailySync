package com.personalmorningalarm.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.DailySchedule
import com.personalmorningalarm.data.model.FamilyCalendar
import com.personalmorningalarm.data.model.FamilyEvent
import com.personalmorningalarm.data.model.RollingTodo
import com.personalmorningalarm.data.model.ScheduleTaskDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime

/**
 * The rendering shared by the Stage 2 content screens and the Today screen, so the
 * same Alfred content reads the same way wherever it's met.
 */
@RunWith(RobolectricTestRunner::class)
class AlfredRendererTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `schedule renders period headings in order, bolded, with bulleted tasks`() {
        val groups = DailySchedule.group(
            listOf(
                ScheduleTaskDto("Dinner", "eve"),
                ScheduleTaskDto("Gym", "am"),
                ScheduleTaskDto("Standup", "pm"),
                ScheduleTaskDto("Read", null)
            )
        )

        val rendered = ScheduleRenderer.format(context, groups)
        val text = rendered.toString()

        assertEquals(
            "Morning\n• Gym\n\nAfternoon\n• Standup\n\nEvening\n• Dinner\n\nAnytime\n• Read",
            text
        )

        // Each heading is bold; the tasks are not.
        val spanned = rendered as Spanned
        val bold = spanned.getSpans(0, rendered.length, StyleSpan::class.java)
            .filter { it.style == Typeface.BOLD }
            .map { text.substring(spanned.getSpanStart(it), spanned.getSpanEnd(it)) }
        assertEquals(listOf("Morning", "Afternoon", "Evening", "Anytime"), bold)
    }

    @Test
    fun `a five-period day renders every heading in day order, Anytime last`() {
        val groups = DailySchedule.group(
            listOf(
                ScheduleTaskDto("Read", null),
                ScheduleTaskDto("Cubs", "eve"),
                ScheduleTaskDto("Standup", "pm"),
                ScheduleTaskDto("Gym", "am"),
                ScheduleTaskDto("Run", "early")
            )
        )

        assertEquals(
            "Early\n• Run\n\nMorning\n• Gym\n\nAfternoon\n• Standup\n\nEvening\n• Cubs\n\nAnytime\n• Read",
            ScheduleRenderer.format(context, groups).toString()
        )
    }

    @Test
    fun `schedule task markup is rendered, never shown raw`() {
        val groups = DailySchedule.group(listOf(ScheduleTaskDto("**Gym** with [[people/sam|Sam]]", "am")))

        val text = ScheduleRenderer.format(context, groups).toString()

        assertEquals("Morning\n• Gym with Sam", text)
        assertFalse(text.contains("*"))
        assertFalse(text.contains("["))
    }

    // --- family calendar layered onto the schedule ---

    private fun familyEvent(summary: String, time: LocalTime?) =
        FamilyEvent(summary, LocalDate.parse("2026-07-24"), time, allDay = time == null)

    @Test
    fun `an all-day event banners the day with no time, above the periods`() {
        val agenda = FamilyCalendar.agenda(
            listOf(ScheduleTaskDto("A33 loop", null)),
            listOf(familyEvent("France", null))
        )

        val text = ScheduleRenderer.format(context, agenda, Color.RED).toString()

        assertEquals("▌ France\n\nAnytime\n• A33 loop", text)
    }

    @Test
    fun `a timed event renders with its start time, interleaved into its period`() {
        val agenda = FamilyCalendar.agenda(
            listOf(ScheduleTaskDto("Weigh-in", "am")),
            listOf(familyEvent("Early shift", LocalTime.of(7, 30)))
        )

        val text = ScheduleRenderer.format(context, agenda, Color.RED).toString()

        // 07:30 is before 09:00, so it heads its own Early group above the Morning item.
        assertEquals("Early\n▌ 07:30  Early shift\n\nMorning\n• Weigh-in", text)
    }

    @Test
    fun `calendar rows are colour-marked and vault rows are not`() {
        val agenda = FamilyCalendar.agenda(
            listOf(ScheduleTaskDto("Weigh-in", "am")),
            listOf(familyEvent("Early shift", LocalTime.of(7, 30)))
        )

        val rendered = ScheduleRenderer.format(context, agenda, Color.RED)
        val text = rendered.toString()
        val spanned = rendered as Spanned
        val marked = spanned.getSpans(0, rendered.length, ForegroundColorSpan::class.java)
            .map { text.substring(spanned.getSpanStart(it), spanned.getSpanEnd(it)) }

        assertEquals(listOf("▌ 07:30  Early shift"), marked)
    }

    @Test
    fun `a calendar summary is shown as sent — it is not vault markup`() {
        // Google summaries aren't vault text, so ** and [[ ]] would be literal.
        val agenda = FamilyCalendar.agenda(emptyList(), listOf(familyEvent("Pilates **class**", LocalTime.of(9, 0))))

        val text = ScheduleRenderer.format(context, agenda, Color.RED).toString()

        assertTrue(text.contains("Pilates **class**"))
    }

    @Test
    fun `a schedule with no calendar renders exactly as it did before`() {
        val groups = DailySchedule.group(listOf(ScheduleTaskDto("Gym", "am")))
        val agenda = FamilyCalendar.agenda(listOf(ScheduleTaskDto("Gym", "am")), emptyList())

        assertEquals(
            ScheduleRenderer.format(context, groups).toString(),
            ScheduleRenderer.format(context, agenda, Color.RED).toString()
        )
    }

    @Test
    fun `chalkboard renders an unticked box per item with the date beneath`() {
        val items = RollingTodo.items(
            listOf(
                ChalkboardTaskDto("Plant the bamboo", "2026-07-05"),
                ChalkboardTaskDto("Fix fence", null)
            )
        )

        val text = ChalkboardRenderer.format(items, Color.GRAY).toString()

        assertEquals("☐  Plant the bamboo\n     5 Jul 2026\n☐  Fix fence", text)
        // The dateless item gets no line of its own, and certainly not "null".
        assertFalse(text.contains("null"))
    }

    @Test
    fun `chalkboard dates are coloured as secondary text`() {
        val items = RollingTodo.items(listOf(ChalkboardTaskDto("Plant the bamboo", "2026-07-05")))

        val rendered = ChalkboardRenderer.format(items, Color.RED)
        val spanned = rendered as Spanned
        val colours = spanned.getSpans(0, rendered.length, ForegroundColorSpan::class.java)

        assertEquals(1, colours.size)
        assertEquals(Color.RED, colours[0].foregroundColor)
        assertEquals(
            "     5 Jul 2026",
            rendered.substring(spanned.getSpanStart(colours[0]), spanned.getSpanEnd(colours[0]))
        )
    }

    @Test
    fun `chalkboard task markup is rendered, never shown raw`() {
        val items = RollingTodo.items(
            listOf(ChalkboardTaskDto("Disable Pi WiFi power-save. See [[pi-admin-guide]] §9a", "2026-07-13"))
        )

        val text = ChalkboardRenderer.format(items, Color.GRAY).toString()

        assertTrue(text.contains("See pi-admin-guide §9a"))
        assertFalse(text.contains("[["))
    }

    @Test
    fun `chalkboard shows ticked-but-unswept items as done, not resurrected`() {
        val items = RollingTodo.items(
            listOf(
                ChalkboardTaskDto("Fix fence", null, "- [x] Fix fence"),
                ChalkboardTaskDto("Plant the bamboo", null, "- [ ] Plant the bamboo")
            )
        )

        val rendered = ChalkboardRenderer.format(items, Color.GRAY)
        val text = rendered.toString()

        assertEquals("☑  Fix fence\n☐  Plant the bamboo", text)
        // Struck through — done, still listed until the overnight sweep.
        val spanned = rendered as Spanned
        val struck = spanned.getSpans(0, rendered.length, android.text.style.StrikethroughSpan::class.java)
        assertEquals(1, struck.size)
        assertEquals(
            "☑  Fix fence",
            text.substring(spanned.getSpanStart(struck[0]), spanned.getSpanEnd(struck[0]))
        )
    }

    @Test
    fun `empty input renders empty, not a stray heading or box`() {
        assertEquals("", ScheduleRenderer.format(context, emptyList()).toString())
        assertEquals("", ChalkboardRenderer.format(emptyList(), Color.GRAY).toString())
    }
}
