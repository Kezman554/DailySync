package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.entity.NoteVerb
import com.personalmorningalarm.data.entity.PendingNoteWrite
import java.util.UUID

/**
 * One row of GET /notes. Nullable for the same reason every other Alfred DTO's
 * fields are: a saved response, or a field the API stops sending, must degrade
 * rather than crash.
 */
data class NoteSummaryDto(
    val id: String? = null,
    val title: String? = null,
    val updated: String? = null,
    val preview: String? = null
)

/** GET /notes/{id}: the note's exact text. */
data class NoteDto(
    val id: String? = null,
    val text: String? = null,
    val updated: String? = null
)

/** One note, ready to render. Plain text: nothing here is ever treated as markup. */
data class NoteRow(
    val id: String,
    val title: String,
    val preview: String,
    /** Written on the phone and not in the vault yet. */
    val pending: Boolean = false,
    /** Alfred refused the text; it's kept on the phone until it's edited or deleted. */
    val refused: Boolean = false
)

/** The notepad's rules, with no Android in sight so they can be tested on the JVM. */
object Notes {

    /** What leaving the editor means for the text in it. */
    enum class Leaving { NOTHING, SAVE, CONFIRM_DELETE }

    /** 36 lowercase hex digits and hyphens, inside Alfred's id rule. Never changes once made. */
    fun newId(): String = UUID.randomUUID().toString()

    /**
     * [kept] is the text the vault or the queue already holds, null if nothing is
     * kept yet (a new note). Blank is Alfred's blank: he refuses it, so it's never
     * sent as a save.
     */
    fun leaving(text: String, kept: String?): Leaving = when {
        text == kept -> Leaving.NOTHING
        text.isNotBlank() -> Leaving.SAVE
        kept == null -> Leaving.NOTHING
        else -> Leaving.CONFIRM_DELETE
    }

    /**
     * [text] the way GET /notes will describe it once it lands: the title is the
     * first non-empty line, the preview is what follows with its spacing collapsed.
     * Mirrors _note_summary in vault-api, so a row doesn't change when it syncs.
     */
    fun summary(id: String, text: String): NoteSummaryDto {
        val lines = text.lines()
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first < 0) return NoteSummaryDto(id = id, title = id, preview = "")
        val rest = lines.drop(first + 1).joinToString(" ").trim().replace(WHITESPACE, " ")
        return NoteSummaryDto(
            id = id,
            title = codePoints(lines[first].trim(), TITLE_MAX),
            preview = codePoints(rest, PREVIEW_MAX)
        )
    }

    /**
     * Alfred's list with the queue on top: unsent notes first, newest first, which
     * is where they'll land. A queued save stands in for the note's row and a queued
     * delete hides it, so a note never shows twice or lingers after being deleted.
     */
    fun merged(notes: List<NoteSummaryDto>, pending: List<PendingNoteWrite>): List<NoteRow> {
        val saves = pending.filter { NoteVerb.fromName(it.verb) == NoteVerb.SAVE }
        val deletes = pending.filter { NoteVerb.fromName(it.verb) == NoteVerb.DELETE && !it.failed }
        val hidden = (saves + deletes).map { it.noteId }.toSet()
        val queued = saves.reversed().map { write ->
            row(summary(write.noteId, write.text), write.noteId, pending = !write.failed, refused = write.failed)
        }
        return queued + notes.mapNotNull { note ->
            note.id?.takeIf { it.isNotBlank() && it !in hidden }?.let { row(note, it) }
        }
    }

    private fun row(note: NoteSummaryDto, id: String, pending: Boolean = false, refused: Boolean = false) =
        NoteRow(id, note.title ?: id, note.preview.orEmpty(), pending, refused)

    /** Cut by code point, as Alfred's are, so an emoji is never split in half. */
    private fun codePoints(text: String, max: Int): String =
        if (text.codePointCount(0, text.length) <= max) text
        else text.substring(0, text.offsetByCodePoints(0, max))

    private val WHITESPACE = Regex("\\s+")
    private const val TITLE_MAX = 120
    private const val PREVIEW_MAX = 120
}
