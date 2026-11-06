package com.personalmorningalarm.util

import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.color.MaterialColors
import com.personalmorningalarm.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Vault authoring syntax must never reach the screen — no literal [[ ]], ** or
 * backticks, except inside code, which is shown as the author typed it.
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
        val rendered = VaultText.render(context, "Fix **fence** — see [[home/garden|garden notes]]")

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

        val rendered = VaultText.render(context, item).toString()

        assertFalse(rendered.contains("[["))
        assertFalse(rendered.contains("]]"))
        assertEquals(true, rendered.endsWith("See shed-guide §9a"))
    }

    // ---- Inline code, and the cases shared with the wall dashboard ----
    //
    // Everything from here down mirrors tests/vaultMarkup.test.ts in AlfredHomeHub,
    // so the same input gives the same text, emphasis and code on both clients.
    // Fixtures are invented: where the dashboard's came from the live vault, these
    // keep the shape and change the words.

    /** Segments as text plus "b", "i", "c" flags, for readable assertions. */
    private fun shape(source: String): List<List<String>> =
        VaultText.parse(source).map { segment ->
            val style = buildString {
                if (segment.bold) append('b')
                if (segment.italic) append('i')
                if (segment.code) append('c')
            }
            if (style.isEmpty()) listOf(segment.text) else listOf(segment.text, style)
        }

    @Test
    fun `a bold lead-in then a dash reads as bold then plain`() {
        assertEquals(
            listOf(listOf("Order the fence panels (two packs)", "b"), listOf(" — or the next garden job")),
            shape("**Order the fence panels (two packs)** — or the next garden job")
        )
    }

    @Test
    fun `plain text comes out byte-identical`() {
        listOf(
            "Buy milk",
            "  two leading spaces,  double spaces  and a trailing one ",
            "Punctuation stays — \"quotes\", ‘curly’, ellipsis… and a lone [ bracket",
            "A single * asterisk survives",
            "Tabs\tstay\ttabs",
            ""
        ).forEach { line ->
            assertEquals(line, VaultText.plainText(line))
            if (line.isNotEmpty()) assertEquals(listOf(listOf(line)), shape(line))
        }
    }

    @Test
    fun `bold, italic and inline code render as styling`() {
        assertEquals(
            listOf(
                listOf("a "), listOf("b", "b"), listOf(" c "), listOf("d", "i"),
                listOf(" e "), listOf("f", "c"), listOf(" g")
            ),
            shape("a **b** c *d* e `f` g")
        )
    }

    @Test
    fun `inline code is literal, with no emphasis or links inside it`() {
        assertEquals(
            listOf(listOf("run "), listOf("**not bold** [[not a link]]", "c"), listOf(" now")),
            shape("run `**not bold** [[not a link]]` now")
        )
    }

    @Test
    fun `emphasis may wrap a code span`() {
        assertEquals(
            listOf(listOf("use ", "b"), listOf("npm ci", "bc"), listOf(" here", "b")),
            shape("**use `npm ci` here**")
        )
    }

    @Test
    fun `wikilinks render as their display text`() {
        assertEquals("see shed-guide", VaultText.plainText("see [[shed-guide]]"))
        assertEquals("see the shed notes", VaultText.plainText("see [[home/shed-guide|the shed notes]]"))
        assertEquals("see note > Heading", VaultText.plainText("see [[note#Heading]]"))
        assertEquals("see Heading", VaultText.plainText("see [[#Heading]]"))
        assertEquals("diagram.png", VaultText.plainText("![[diagram.png]]"))
    }

    @Test
    fun `a wikilink inside emphasis, and emphasis inside an alias`() {
        assertEquals(listOf(listOf("read shed-guide first", "b")), shape("**read [[shed-guide]] first**"))
        assertEquals(listOf(listOf("soft", "i")), shape("[[note|*soft*]]"))
    }

    @Test
    fun `target, heading and alias are trimmed`() {
        assertEquals("Alias", VaultText.stripWikiLinks("[[note | Alias ]]"))
        assertEquals("note", VaultText.stripWikiLinks("[[ note ]]"))
        assertEquals("note > Head", VaultText.stripWikiLinks("[[ note # Head ]]"))
    }

    @Test
    fun `an unclosed link does not stop the emphasis after it`() {
        assertEquals(
            listOf(listOf("start [[unclosed and "), listOf("bold", "b"), listOf(" after")),
            shape("start [[unclosed and **bold** after")
        )
    }

    @Test
    fun `the known boundary - two lone asterisks read as italic`() {
        // The dashboard does the same. Fix it in both or neither.
        assertEquals("5  3 = 15 and a lone  survives", VaultText.plainText("5 * 3 = 15 and a lone * survives"))
    }

    @Test
    fun `a one-long-line title loses its emphasis markers`() {
        assertEquals(
            "Garden Shed Plan Drawn up: March 2026 Status: Waiting on timber",
            VaultText.plainText("Garden Shed Plan **Drawn up:** March 2026 **Status:** Waiting on timber")
        )
    }

    @Test
    fun `markdown links and headings are out of scope and left as written`() {
        assertEquals(
            "Shed Roof Notes ## Felt Part of: [Garden Index](./00-garden.md)",
            VaultText.plainText("Shed Roof Notes ## Felt **Part of:** [Garden Index](./00-garden.md)")
        )
    }

    @Test
    fun `private-use characters in the source disable code handling rather than confuse it`() {
        assertEquals("\uE000 and `code`", VaultText.plainText("\uE000 and `code`"))
        assertEquals(listOf(listOf("\uE000 and `code`")), shape("\uE000 and `code`"))
    }

    // ---- The build log's parity kinds the shared cases leave out ----

    @Test
    fun `heading links with an alias, and aliases on an in-note link`() {
        assertEquals("the roof", VaultText.plainText("[[shed-guide#Roofing|the roof]]"))
        assertEquals("the roof", VaultText.plainText("[[#Roofing|the roof]]"))
    }

    @Test
    fun `embeds resolve like links, wherever they sit in the line`() {
        assertEquals("see diagram.png here", VaultText.plainText("see ![[diagram.png]] here"))
        assertEquals("shed-guide > Roofing", VaultText.plainText("![[shed-guide#Roofing]]"))
        assertEquals("the plan", VaultText.plainText("![[diagram.png|the plan]]"))
    }

    @Test
    fun `empty links resolve to nothing, or stay as written when the pattern refuses them`() {
        assertEquals("a  b", VaultText.plainText("a [[]] b"))
        assertEquals("a  b", VaultText.plainText("a ![[]] b"))
        assertEquals("a  b", VaultText.plainText("a [[ ]] b"))
        assertEquals("alias", VaultText.plainText("[[|alias]]"))
        // An empty heading or alias has nothing for its group to match.
        assertEquals("[[#]]", VaultText.plainText("[[#]]"))
        assertEquals("[[note|]]", VaultText.plainText("[[note|]]"))
    }

    @Test
    fun `an unclosed link is left alone with no emphasis around it`() {
        assertEquals(listOf(listOf("Sort [[unfinished link")), shape("Sort [[unfinished link"))
    }

    @Test
    fun `an unclosed link does not stop the code after it`() {
        assertEquals(
            listOf(listOf("start [[unclosed "), listOf("code", "c"), listOf(" after")),
            shape("start [[unclosed `code` after")
        )
    }

    @Test
    fun `emphasis in an alias sits beside plain text around the link`() {
        assertEquals(
            listOf(listOf("see "), listOf("the plan", "b"), listOf(" soon")),
            shape("see [[shed-guide|**the plan**]] soon")
        )
    }

    @Test
    fun `a link inside emphasis keeps every form of display text`() {
        assertEquals(listOf(listOf("the plan", "i")), shape("*[[shed-guide|the plan]]*"))
        assertEquals(listOf(listOf("a > b", "b")), shape("**[[a#b]]**"))
    }

    @Test
    fun `trimmed links inside emphasis and beside code`() {
        assertEquals(
            listOf(listOf("Alias", "b"), listOf(" and "), listOf(" x ", "c")),
            shape("**[[note | Alias ]]** and ` x `")
        )
    }

    @Test
    fun `an asterisk inside code does not pair with one outside it`() {
        assertEquals(
            listOf(listOf("2 * 3 and "), listOf("a * b", "c")),
            shape("2 * 3 and `a * b`")
        )
    }

    @Test
    fun `plain lines with a pair of markers that are not code stay as written`() {
        listOf("a lone ` backtick", "an empty `` pair", "split `across\nlines`").forEach { line ->
            assertEquals(line, VaultText.plainText(line))
            assertEquals(listOf(listOf(line)), shape(line))
        }
    }

    @Test
    fun `code in a link's alias is still code`() {
        assertEquals(listOf(listOf("x", "c")), shape("[[note|`x`]]"))
    }

    @Test
    fun `italic wraps code the way bold does`() {
        assertEquals(listOf(listOf("x", "ic")), shape("*`x`*"))
    }

    @Test
    fun `several code spans keep their own contents and order`() {
        assertEquals(
            listOf(listOf("one", "c"), listOf(" then "), listOf("two", "c")),
            shape("`one` then `two`")
        )
    }

    // ---- resolveLinks: what the data layer stores for render to read later ----

    @Test
    fun `resolveLinks resolves links but leaves code and emphasis as authored`() {
        assertEquals(
            "Fix **fence**, see garden notes and `[[not a link]]`",
            VaultText.resolveLinks("Fix **fence**, see [[home/garden|garden notes]] and `[[not a link]]`")
        )
    }

    @Test
    fun `resolving links first changes nothing a reader would see`() {
        listOf(
            "run `**not bold** [[not a link]]` now",
            "**use `npm ci` here** with [[shed-guide]]",
            "[[note|`x`]] then `[[y]]`",
            "\uE000 and `[[code]]`",
            "start [[unclosed `code` after"
        ).forEach { line ->
            assertEquals(VaultText.parse(line), VaultText.parse(VaultText.resolveLinks(line)))
        }
    }

    // ---- render: the spans a surface shows ----

    private val context: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(),
        R.style.Theme_PersonalMorningAlarm
    )

    @Test
    fun `render drops the backticks and sets code apart with colorSurface2`() {
        val rendered = VaultText.render(context, "Run `[[not a link]]` *today*")

        assertEquals("Run [[not a link]] today", rendered.toString())
        val spanned = rendered as Spanned
        val code = spanned.getSpans(0, rendered.length, BackgroundColorSpan::class.java).single()
        assertEquals("[[not a link]]", rendered.substring(spanned.getSpanStart(code), spanned.getSpanEnd(code)))
        assertEquals(MaterialColors.getColor(context, R.attr.colorSurface2, "test"), code.backgroundColor)
    }

    @Test
    fun `render never changes the typeface of code`() {
        val rendered = VaultText.render(context, "`x`") as Spanned

        assertEquals(0, rendered.getSpans(0, rendered.length, TypefaceSpan::class.java).size)
    }

    @Test
    fun `bold around code spans the whole run and the code keeps its background`() {
        val rendered = VaultText.render(context, "**bold around `code`**")

        assertEquals("bold around code", rendered.toString())
        val spanned = rendered as Spanned
        val bold = spanned.getSpans(0, rendered.length, StyleSpan::class.java)
        assertTrue(bold.all { it.style == Typeface.BOLD })
        assertEquals(
            "bold around code",
            bold.sortedBy { spanned.getSpanStart(it) }
                .joinToString("") { rendered.substring(spanned.getSpanStart(it), spanned.getSpanEnd(it)) }
        )
        val code = spanned.getSpans(0, rendered.length, BackgroundColorSpan::class.java).single()
        assertEquals("code", rendered.substring(spanned.getSpanStart(code), spanned.getSpanEnd(code)))
    }
}
