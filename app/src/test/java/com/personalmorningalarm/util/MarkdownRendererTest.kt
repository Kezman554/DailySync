package com.personalmorningalarm.util

import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Markdown in Alfred's task strings must never reach the screen as literal
 * asterisks — it's rendered as emphasis instead.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Text with no emphasis comes back unspanned, so plain input has no spans. */
    private fun spansOf(rendered: CharSequence): List<Pair<Int, String>> {
        val spanned = rendered as? Spanned ?: return emptyList()
        return spanned.getSpans(0, rendered.length, StyleSpan::class.java)
            .map { it.style to rendered.substring(spanned.getSpanStart(it), spanned.getSpanEnd(it)) }
    }

    @Test
    fun `bold markers are rendered, not shown`() {
        val rendered = MarkdownRenderer.render(context, "Go to the **gym**")

        assertEquals("Go to the gym", rendered.toString())
        assertEquals(listOf(Typeface.BOLD to "gym"), spansOf(rendered))
    }

    @Test
    fun `italic markers are rendered, not shown`() {
        val rendered = MarkdownRenderer.render(context, "Call *Mum*")

        assertEquals("Call Mum", rendered.toString())
        assertEquals(listOf(Typeface.ITALIC to "Mum"), spansOf(rendered))
    }

    @Test
    fun `bold and italic in one task are each rendered`() {
        val rendered = MarkdownRenderer.render(context, "**Gym** then *shower*")

        assertEquals("Gym then shower", rendered.toString())
        assertEquals(
            listOf(Typeface.BOLD to "Gym", Typeface.ITALIC to "shower"),
            spansOf(rendered)
        )
    }

    @Test
    fun `bold wins over italic so double markers never leak a stray asterisk`() {
        val rendered = MarkdownRenderer.render(context, "**a** and **b**")

        assertEquals("a and b", rendered.toString())
        assertEquals(listOf(Typeface.BOLD to "a", Typeface.BOLD to "b"), spansOf(rendered))
    }

    @Test
    fun `plain text passes through untouched`() {
        val rendered = MarkdownRenderer.render(context, "Walk the dog")

        assertEquals("Walk the dog", rendered.toString())
        assertTrue(spansOf(rendered).isEmpty())
    }

    @Test
    fun `an unpaired asterisk is left alone rather than eating the rest of the task`() {
        val rendered = MarkdownRenderer.render(context, "2 * 3 sets")

        assertEquals("2 * 3 sets", rendered.toString())
    }
}
