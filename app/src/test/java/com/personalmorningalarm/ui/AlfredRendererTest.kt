package com.personalmorningalarm.ui

import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.RollingTodo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The rendering shared by the Stage 2 content screens and the Today screen, so the
 * same Alfred content reads the same way wherever it's met.
 */
@RunWith(RobolectricTestRunner::class)
class AlfredRendererTest {

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
            listOf(ChalkboardTaskDto("Reseal the shed roof. See [[shed-guide]] §9a", "2026-07-13"))
        )

        val text = ChalkboardRenderer.format(items, Color.GRAY).toString()

        assertTrue(text.contains("See shed-guide §9a"))
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
    fun `empty input renders empty, not a stray box`() {
        assertEquals("", ChalkboardRenderer.format(emptyList(), Color.GRAY).toString())
    }
}
