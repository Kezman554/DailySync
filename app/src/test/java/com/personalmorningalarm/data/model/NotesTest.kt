package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.entity.NoteVerb
import com.personalmorningalarm.data.entity.PendingNoteWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The blank-note rules, the merged list, and the summary that mirrors vault-api's. */
class NotesTest {

    private fun save(id: String, text: String, order: Long, failed: Boolean = false) =
        PendingNoteWrite(id = order, noteId = id, verb = NoteVerb.SAVE.name, text = text, createdAt = 0, failed = failed)

    private fun delete(id: String, order: Long, failed: Boolean = false) =
        PendingNoteWrite(id = order, noteId = id, verb = NoteVerb.DELETE.name, text = "", createdAt = 0, failed = failed)

    private val shed = NoteSummaryDto("shed", "Shed", "2026-03-14T09:00:00Z", "Shelves and hooks")
    private val attic = NoteSummaryDto("attic", "Attic", "2026-03-13T09:00:00Z", "")

    // ---- Blank rules ----

    @Test
    fun `a new note left blank isn't saved`() {
        assertEquals(Notes.Leaving.NOTHING, Notes.leaving("", kept = null))
        assertEquals(Notes.Leaving.NOTHING, Notes.leaving("  \n\t ", kept = null))
    }

    @Test
    fun `a new note with text is saved`() {
        assertEquals(Notes.Leaving.SAVE, Notes.leaving("Buy a hinge", kept = null))
    }

    @Test
    fun `an existing note emptied asks before deleting`() {
        assertEquals(Notes.Leaving.CONFIRM_DELETE, Notes.leaving("", kept = "Buy a hinge"))
        assertEquals(Notes.Leaving.CONFIRM_DELETE, Notes.leaving(" \n ", kept = "Buy a hinge"))
    }

    @Test
    fun `an unchanged note writes nothing`() {
        assertEquals(Notes.Leaving.NOTHING, Notes.leaving("Buy a hinge", kept = "Buy a hinge"))
    }

    @Test
    fun `an edit is saved, spacing alone included`() {
        assertEquals(Notes.Leaving.SAVE, Notes.leaving("Buy two hinges", kept = "Buy a hinge"))
        assertEquals(Notes.Leaving.SAVE, Notes.leaving("Buy a hinge\n", kept = "Buy a hinge"))
    }

    // ---- Ids ----

    @Test
    fun `a new id fits Alfred's rule`() {
        val id = Notes.newId()

        assertTrue(id, Regex("[a-z0-9-]{1,64}").matches(id))
        assertNotEquals(id, Notes.newId())
    }

    // ---- Summary ----

    @Test
    fun `the title is the first non-empty line and the preview is what follows`() {
        val summary = Notes.summary("n", "\n  \n  Title line  \n\n second\tline\n   third  ")

        assertEquals("Title line", summary.title)
        assertEquals("second line third", summary.preview)
    }

    @Test
    fun `a one-line note has an empty preview`() {
        assertEquals("", Notes.summary("n", "Just this").preview)
    }

    @Test
    fun `title and preview stop at 120 characters`() {
        val summary = Notes.summary("n", "t".repeat(200) + "\n" + "p".repeat(200))

        assertEquals(120, summary.title!!.length)
        assertEquals(120, summary.preview!!.length)
    }

    @Test
    fun `the cut never splits an emoji`() {
        val summary = Notes.summary("n", "🔧".repeat(200))

        assertEquals("🔧".repeat(120), summary.title)
    }

    @Test
    fun `markup stays as it was typed`() {
        val summary = Notes.summary("n", "- [ ] **bold** [[link]]\n- [x] `code`")

        assertEquals("- [ ] **bold** [[link]]", summary.title)
        assertEquals("- [x] `code`", summary.preview)
    }

    // ---- Merged list ----

    @Test
    fun `with nothing queued the list is Alfred's, in Alfred's order`() {
        assertEquals(
            listOf(NoteRow("shed", "Shed", "Shelves and hooks"), NoteRow("attic", "Attic", "")),
            Notes.merged(listOf(shed, attic), emptyList())
        )
    }

    @Test
    fun `a queued new note shows first, marked as waiting`() {
        val rows = Notes.merged(listOf(shed), listOf(save("garage", "Garage\nRacking", 1)))

        assertEquals(listOf("garage", "shed"), rows.map { it.id })
        assertEquals(NoteRow("garage", "Garage", "Racking", pending = true), rows.first())
    }

    @Test
    fun `queued notes are newest first`() {
        val rows = Notes.merged(emptyList(), listOf(save("older", "Older", 1), save("newer", "Newer", 2)))

        assertEquals(listOf("newer", "older"), rows.map { it.id })
    }

    @Test
    fun `a queued edit stands in for the note, not beside it`() {
        val rows = Notes.merged(listOf(shed, attic), listOf(save("attic", "Attic, revised", 1)))

        assertEquals(listOf("attic", "shed"), rows.map { it.id })
        assertEquals("Attic, revised", rows.first().title)
    }

    @Test
    fun `a queued delete hides the note`() {
        assertEquals(listOf("attic"), Notes.merged(listOf(shed, attic), listOf(delete("shed", 1))).map { it.id })
    }

    @Test
    fun `a refused note stays listed and says so`() {
        val row = Notes.merged(emptyList(), listOf(save("shed", "Too long", 1, failed = true))).single()

        assertEquals(NoteRow("shed", "Too long", "", pending = false, refused = true), row)
    }

    @Test
    fun `a refused delete puts the note back`() {
        assertEquals(listOf("shed"), Notes.merged(listOf(shed), listOf(delete("shed", 1, failed = true))).map { it.id })
    }

    @Test
    fun `a write this build can't read leaves the note as Alfred has it`() {
        val unknown = PendingNoteWrite(id = 1, noteId = "shed", verb = "ARCHIVE", text = "?", createdAt = 0)

        assertEquals(listOf(NoteRow("shed", "Shed", "Shelves and hooks")), Notes.merged(listOf(shed), listOf(unknown)))
    }

    @Test
    fun `a saved row with no id is dropped and one with no title falls back to its id`() {
        val rows = Notes.merged(listOf(NoteSummaryDto(), NoteSummaryDto(id = "bare")), emptyList())

        assertEquals(listOf(NoteRow("bare", "bare", "")), rows)
    }
}
