package com.personalmorningalarm.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The latest unsent write for one note. Every note write goes through here, online
 * or not, so nothing typed depends on Alfred answering.
 *
 * Unlike the other queues this holds an outcome rather than a history: only the
 * last thing done to a note matters, so [noteId] is unique and a new write replaces
 * the old row. That replacement gets a new [id], which is what keeps it safe from a
 * replay already in flight: when the replay finishes it deletes the row it sent, by
 * [id], and so can't take a newer save with it.
 *
 * [failed] means Alfred read the text and refused it (a 422: over the length limit).
 * The row keeps the text, so the note is never lost, and a later edit replaces it.
 */
@Entity(
    tableName = "pending_note_writes",
    indices = [Index(value = ["noteId"], unique = true)]
)
data class PendingNoteWrite(
    /** AUTOINCREMENT, so never reused: replay order, and the identity of this exact write. */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: String,
    /** A [NoteVerb] name. Stored as text; unknown names are failed, not fatal. */
    val verb: String,
    /** What a SAVE sends, exactly as typed. Empty for a DELETE. */
    val text: String,
    val createdAt: Long,
    val failed: Boolean = false
)

enum class NoteVerb {
    SAVE,
    DELETE;

    companion object {
        /** Null for a name this build doesn't know — the flush fails that entry rather than crash. */
        fun fromName(name: String): NoteVerb? = entries.firstOrNull { it.name == name }
    }
}
