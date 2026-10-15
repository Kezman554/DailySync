package com.personalmorningalarm.challenge

import com.personalmorningalarm.data.entity.NfcTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcCheckpointManagerTest {

    private fun tags(n: Int): List<NfcTag> =
        (1..n).map { NfcTag(id = it.toLong(), tagId = "TAG$it", label = "Tag $it", location = "Room $it") }

    private fun sequenceOf(manager: NfcCheckpointManager): List<String> {
        val ids = mutableListOf<String>()
        // Walk the manager by always scanning the tag it currently wants.
        while (!manager.isComplete) {
            val expected = manager.currentTag!!
            ids.add(expected.tagId)
            manager.onTagScanned(expected.tagId)
        }
        return ids
    }

    @Test
    fun `length below tag count picks that many unique tags`() {
        val manager = NfcCheckpointManager(tags(5), sequenceLength = 3)
        assertEquals(3, manager.total)
        val seq = sequenceOf(manager)
        assertEquals(3, seq.size)
        assertEquals("no repeats expected when pool is large enough", seq.size, seq.toSet().size)
    }

    @Test
    fun `length equal to tag count uses every tag once`() {
        val manager = NfcCheckpointManager(tags(4), sequenceLength = 4)
        assertEquals(4, manager.total)
        val seq = sequenceOf(manager)
        assertEquals(setOf("TAG1", "TAG2", "TAG3", "TAG4"), seq.toSet())
    }

    @Test
    fun `length above tag count repeats tags but never back to back`() {
        repeat(200) { // randomised — run many times to catch adjacency violations
            val manager = NfcCheckpointManager(tags(2), sequenceLength = 5)
            assertEquals(5, manager.total)
            val seq = sequenceOf(manager)
            assertEquals(5, seq.size)
            for (i in 1 until seq.size) {
                assertNotEquals("tag repeated back-to-back at $i in $seq", seq[i - 1], seq[i])
            }
        }
    }

    @Test
    fun `single tag with long sequence falls back to repeating that tag`() {
        // Adjacency is impossible to avoid with one tag; we still produce the length.
        val manager = NfcCheckpointManager(tags(1), sequenceLength = 3)
        assertEquals(3, manager.total)
        assertEquals(listOf("TAG1", "TAG1", "TAG1"), sequenceOf(manager))
    }

    @Test
    fun `empty pool yields an immediately complete sequence`() {
        val manager = NfcCheckpointManager(emptyList(), sequenceLength = 5)
        assertEquals(0, manager.total)
        assertTrue(manager.isComplete)
        assertNull(manager.currentTag)
        assertEquals(CheckpointResult.AlreadyComplete, manager.onTagScanned("TAG1"))
    }

    @Test
    fun `order is randomised across mornings`() {
        // Build the morning's full sequence many times; the tap order must not be
        // identical every time (the shuffle prevents autopilot). With 5 tags the
        // odds of the same order recurring by chance are negligible.
        val orders = (1..50).map { sequenceOf(NfcCheckpointManager(tags(5), sequenceLength = 5)) }
        assertTrue("sequence order never varied — not randomised", orders.toSet().size > 1)
    }

    @Test
    fun `wrong tag does not advance the sequence`() {
        val manager = NfcCheckpointManager(tags(3), sequenceLength = 3)
        val firstExpected = manager.currentTag!!
        val result = manager.onTagScanned("NOT_A_REAL_TAG")
        assertTrue(result is CheckpointResult.Wrong)
        assertEquals(firstExpected.tagId, manager.currentTag!!.tagId)
        assertEquals(1, manager.currentNumber)
        // Not a registered tag: nothing to name.
        assertEquals(null, (result as CheckpointResult.Wrong).tapped)
    }

    @Test
    fun `wrong tag names the registered tag that was tapped`() {
        val manager = NfcCheckpointManager(tags(3), sequenceLength = 3)
        val expected = manager.currentTag!!
        val other = tags(3).first { it.tagId != expected.tagId }

        val result = manager.onTagScanned(other.tagId.lowercase()) as CheckpointResult.Wrong

        assertEquals(expected.tagId, result.expected.tagId)
        assertEquals(other.label, result.tapped?.label)
    }
}
