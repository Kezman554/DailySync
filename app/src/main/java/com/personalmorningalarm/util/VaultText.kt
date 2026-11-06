package com.personalmorningalarm.util

import android.content.Context

/**
 * Makes a raw line from the Obsidian vault fit to show on screen.
 *
 * Alfred serves note text verbatim, so it still carries the vault's authoring
 * syntax — `[[wikilinks]]`, `**emphasis**` and inline code. None of that means
 * anything to someone half-awake at 6am, so it is resolved to what a reader would
 * see in Obsidian rather than shown raw.
 *
 * The Alfred-backed screens all share this: they read the same vault, so text
 * from one can carry anything text from another can.
 *
 * The wall dashboard reads the same strings through its own port of this file,
 * src/dashboard/frontend/src/components/vaultMarkup.ts in AlfredHomeHub. The two
 * must agree on what a string means, so a rule changed here changes there too;
 * VaultTextTest holds the cases the two share.
 *
 * Known boundary, kept on both sides: two lone asterisks in one line read as an
 * italic span between them. Fix it in both or neither.
 *
 * Not a markdown parser: inline emphasis, inline code and wikilinks only.
 * Headings, lists, tables, block code and [text](url) links stay as written.
 */
object VaultText {

    /** A run of text sharing one style. A code run can also be bold or italic. */
    data class Segment(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false
    )

    // target, optional #section, optional |alias — mirroring Obsidian's own link
    // form. A leading `!` (an embed) is consumed too. None of the parts may span
    // a bracket, so an unclosed `[[` is left alone rather than eating the line.
    private val WIKILINK = Regex("""!?\[\[([^\[\]|#]*)(?:#([^\[\]|]+))?(?:\|([^\[\]]+))?]]""")

    // Bold is listed first so `**x**` is matched as bold rather than as an italic
    // `*` wrapping `*x*`. Neither alternative may span a line break.
    private val EMPHASIS = Regex("""\*\*([^*\n]+)\*\*|\*([^*\n]+)\*""")

    // One backtick each side, no backtick or newline inside.
    private val INLINE_CODE = Regex("""`([^`\n]+)`""")

    // Code spans are lifted out first and stood in for by private-use characters,
    // so links and emphasis resolve over the whole line (emphasis may wrap a code
    // span) without touching the code's contents. A source already holding
    // private-use characters skips code handling rather than risk reading its own
    // text as a placeholder.
    private const val PLACEHOLDER_BASE = 0xE000
    private val PRIVATE_USE = Regex("[-]")

    /**
     * Replaces each wikilink with the text Obsidian would render: the alias if
     * there is one, otherwise the target, with a `#section` shown as "target >
     * section" the way Obsidian displays it. Blind to inline code: text that may
     * hold code goes through [resolveLinks] or [parse] instead.
     */
    fun stripWikiLinks(source: String): String {
        if (!source.contains("[[")) return source
        return WIKILINK.replace(source) { match ->
            val target = match.groupValues[1].trim()
            val section = match.groupValues[2].trim()
            val alias = match.groupValues[3].trim()
            when {
                alias.isNotEmpty() -> alias
                target.isEmpty() -> section // [[#Heading]] — a link within the note
                section.isEmpty() -> target
                else -> "$target > $section"
            }
        }
    }

    /**
     * Wikilinks resolved, everything else still as authored — for the data layer,
     * whose strings are later handed to [render]. Unlike [stripWikiLinks] it leaves
     * a link inside backticks alone, because by the time [render] sees the string
     * it could no longer tell that code from what the author typed.
     */
    fun resolveLinks(source: String): String {
        if (!source.contains("[[")) return source
        val codes = mutableListOf<String>()
        val resolved = stripWikiLinks(liftCode(source, codes))
        return buildString {
            for (char in resolved) {
                val code = codes.getOrNull(char.code - PLACEHOLDER_BASE)
                if (code != null) append('`').append(code).append('`') else append(char)
            }
        }
    }

    /** [source] as the runs a reader would see, with all of its markup resolved. */
    fun parse(source: String): List<Segment> {
        // The byte-identical path: nothing that could be markup, nothing touched.
        if (!source.contains('*') && !source.contains("[[") && !source.contains('`')) {
            return if (source.isEmpty()) emptyList() else listOf(Segment(source))
        }

        val codes = mutableListOf<String>()
        val segments = emphasise(stripWikiLinks(liftCode(source, codes)))
        if (codes.isEmpty()) return segments

        // Put the code spans back, each as its own segment inheriting the emphasis
        // of the run it sat in.
        val out = mutableListOf<Segment>()
        for (segment in segments) {
            val run = StringBuilder()
            fun flush() {
                if (run.isNotEmpty()) out += segment.copy(text = run.toString())
                run.clear()
            }
            for (char in segment.text) {
                val code = codes.getOrNull(char.code - PLACEHOLDER_BASE)
                if (code != null) {
                    flush()
                    out += segment.copy(text = code, code = true)
                } else {
                    run.append(char)
                }
            }
            flush()
        }
        return out
    }

    /** The same text with the markup resolved and no styling. */
    fun plainText(source: String): String = parse(source).joinToString("") { it.text }

    /** Display-ready text: markup resolved, and rendered as styling from [context]'s theme. */
    fun render(context: Context, source: String): CharSequence = MarkdownRenderer.render(context, source)

    /** [source] with each code span swapped for a placeholder, its body added to [codes]. */
    private fun liftCode(source: String, codes: MutableList<String>): String {
        if (!source.contains('`') || PRIVATE_USE.containsMatchIn(source)) return source
        return INLINE_CODE.replace(source) { match ->
            codes += match.groupValues[1]
            (PLACEHOLDER_BASE + codes.lastIndex).toChar().toString()
        }
    }

    /** Splits text with no code in it into plain, bold and italic runs. */
    private fun emphasise(source: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        var cursor = 0
        for (match in EMPHASIS.findAll(source)) {
            if (match.range.first > cursor) segments += Segment(source.substring(cursor, match.range.first))
            val bold = match.groupValues[1]
            segments += if (bold.isNotEmpty()) {
                Segment(bold, bold = true)
            } else {
                Segment(match.groupValues[2], italic = true)
            }
            cursor = match.range.last + 1
        }
        if (cursor < source.length) segments += Segment(source.substring(cursor))
        return segments
    }
}
