package com.personalmorningalarm.util

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Vault authoring syntax must never reach the screen — no literal [[ ]] or **.
 */
@RunWith(RobolectricTestRunner::class)
class VaultTextTest {

    @Test
    fun `a plain wikilink becomes its target`() {
        assertEquals(
            "See shed-guide §9a",
            VaultText.stripWikiLinks("See [[shed-guide]] §9a")
        )
    }

    @Test
    fun `an aliased wikilink becomes the alias`() {
        assertEquals(
            "Ask Jane about it",
            VaultText.stripWikiLinks("Ask [[people/jane-doe|Jane]] about it")
        )
    }

    @Test
    fun `a section link reads the way Obsidian shows it`() {
        assertEquals(
            "Check shed-guide > Roofing",
            VaultText.stripWikiLinks("Check [[shed-guide#Roofing]]")
        )
    }

    @Test
    fun `an aliased section link still prefers the alias`() {
        assertEquals(
            "Check the roofing notes",
            VaultText.stripWikiLinks("Check [[shed-guide#Roofing|the roofing notes]]")
        )
    }

    @Test
    fun `a link to a heading in the same note keeps the heading`() {
        assertEquals("See Budget below", VaultText.stripWikiLinks("See [[#Budget]] below"))
    }

    @Test
    fun `an embed loses its bang too`() {
        assertEquals("diagram", VaultText.stripWikiLinks("![[diagram]]"))
    }

    @Test
    fun `several links in one task are all resolved`() {
        assertEquals(
            "Link shed-guide to garden-room",
            VaultText.stripWikiLinks("Link [[shed-guide]] to [[projects/garden-room|garden-room]]")
        )
    }

    @Test
    fun `text without links is untouched`() {
        val plain = "Trim front hedges"
        assertEquals(plain, VaultText.stripWikiLinks(plain))
    }

    @Test
    fun `an unclosed bracket is left alone rather than eating the line`() {
        val broken = "Sort [[unfinished link"
        assertEquals(broken, VaultText.stripWikiLinks(broken))
    }

    @Test
    fun `render resolves links and emphasis together, leaving no markup`() {
        val rendered = VaultText.render("Fix **fence** — see [[home/garden|garden notes]]")

        assertEquals("Fix fence — see garden notes", rendered.toString())
        assertFalse(rendered.toString().contains("["))
        assertFalse(rendered.toString().contains("*"))

        val spanned = rendered as Spanned
        val spans = spanned.getSpans(0, rendered.length, StyleSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(Typeface.BOLD, spans[0].style)
        assertEquals("fence", rendered.substring(spanned.getSpanStart(spans[0]), spanned.getSpanEnd(spans[0])))
    }

    /**
     * A full-length item in the shape real ones take (parenthetical, dash, slash,
     * a wikilink and a section reference) — the case that motivated this.
     */
    @Test
    fun `a full-length rolling-todo item comes out clean`() {
        val item = "Reseal the shed roof (north side) — stops it leaking " +
            "overnight; do before/with the gutter work. See [[shed-guide]] §9a"

        val rendered = VaultText.render(item).toString()

        assertFalse(rendered.contains("[["))
        assertFalse(rendered.contains("]]"))
        assertEquals(true, rendered.endsWith("See shed-guide §9a"))
    }
}
