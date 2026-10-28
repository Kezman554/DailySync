package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.entity.PendingInboxWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turning `/inbox` into display-ready captures, and layering the offline queue on top. */
class InboxTest {

    private fun capture(filename: String?, content: String? = "") =
        InboxCaptureDto(filename, content)

    private fun pending(text: String, failed: Boolean = false) =
        PendingInboxWrite(text = text, createdAt = 0L, failed = failed)

    @Test
    fun `a vault filename becomes a title and a captured-at`() {
        val single = Inbox.captures(
            listOf(capture("2026-07-17-1356-allotment-layout-ideas.md"))
        ).single()

        assertEquals("Allotment layout ideas", single.title)
        assertEquals("17 Jul, 13:56", single.captured)
    }

    @Test
    fun `a day-of-month keeps no leading zero`() {
        val single = Inbox.captures(listOf(capture("2026-05-05-0902-a-thought.md"))).single()

        assertEquals("5 May, 09:02", single.captured)
    }

    @Test
    fun `a hand-named file keeps its own name and has no captured-at`() {
        val single = Inbox.captures(listOf(capture("scratch notes.md"))).single()

        assertEquals("scratch notes", single.title)
        assertNull(single.captured)
    }

    @Test
    fun `API order is kept — Alfred already serves newest first`() {
        val titles = Inbox.captures(
            listOf(
                capture("2026-07-17-1356-newest.md"),
                capture("2026-05-21-2252-middle.md"),
                capture("2026-05-10-2100-oldest.md")
            )
        ).map { it.title }

        assertEquals(listOf("Newest", "Middle", "Oldest"), titles)
    }

    @Test
    fun `a capture with no filename is dropped rather than shown blank`() {
        assertTrue(Inbox.captures(listOf(capture(null), capture(" "))).isEmpty())
    }

    @Test
    fun `the preview resolves wikilinks and stops after three non-blank lines`() {
        val single = Inbox.captures(
            listOf(capture("2026-07-17-1356-x.md", "One [[a-link]]\n\n  Two  \nThree\nFour"))
        ).single()

        assertEquals("One a-link\nTwo\nThree", single.preview)
    }

    @Test
    fun `a body-less capture previews as empty rather than crashing`() {
        val single = Inbox.captures(listOf(capture("2026-07-17-1356-x.md", null))).single()

        assertEquals("", single.preview)
    }

    @Test
    fun `queued captures show newest first, above the vault's own`() {
        val merged = Inbox.merged(
            listOf(capture("2026-07-17-1356-from-the-vault.md")),
            listOf(pending("Queued first"), pending("Queued second"))
        )

        // Queue is FIFO, so its newest is last — reversed for display.
        assertEquals(
            listOf("Queued second", "Queued first", "From the vault"),
            merged.map { it.title }
        )
        assertTrue(merged[0].pending)
        assertTrue(merged[1].pending)
        assertFalse(merged[2].pending)
    }

    @Test
    fun `a queued capture is titled by its first non-blank line`() {
        val merged = Inbox.merged(emptyList(), listOf(pending("\n  Buy dumbbells  \nand a mat")))

        assertEquals("Buy dumbbells", merged.single().title)
        assertEquals("Buy dumbbells\nand a mat", merged.single().preview)
    }

    @Test
    fun `refused captures are left out of the list — the notice carries them instead`() {
        val merged = Inbox.merged(emptyList(), listOf(pending("Refused", failed = true)))

        assertTrue(merged.isEmpty())
    }
}
