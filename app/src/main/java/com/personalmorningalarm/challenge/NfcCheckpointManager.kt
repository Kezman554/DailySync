package com.personalmorningalarm.challenge

import com.personalmorningalarm.data.entity.NfcTag

/**
 * Drives the Stage 2 NFC checkpoint sequence. A fresh [sequenceLength]-long order
 * is built at construction (i.e. each morning) so the user can't go on autopilot.
 * Feed each scanned tag's hardware id to [onTagScanned] and act on the result.
 *
 * Sequence rules:
 *  - [sequenceLength] <= number of tags: pick that many unique tags (no repeats).
 *  - [sequenceLength] > number of tags: tags repeat to reach the length, but never
 *    the same tag twice in a row (unavoidable only when a single tag is registered).
 */
class NfcCheckpointManager(private val tags: List<NfcTag>, sequenceLength: Int) {

    private val ordered: List<NfcTag> = buildSequence(tags, sequenceLength)
    private var index = 0

    val total: Int = ordered.size

    /** 1-based number of the checkpoint currently being sought. */
    val currentNumber: Int get() = (index + 1).coerceAtMost(total)

    /** The tag the user must tap next, or null once the sequence is complete. */
    val currentTag: NfcTag? get() = ordered.getOrNull(index)

    val isComplete: Boolean get() = index >= total

    fun onTagScanned(tagId: String): CheckpointResult {
        val expected = currentTag ?: return CheckpointResult.AlreadyComplete
        return if (tagId.equals(expected.tagId, ignoreCase = true)) {
            index++
            if (isComplete) CheckpointResult.SequenceComplete
            else CheckpointResult.Correct(currentTag!!)
        } else {
            CheckpointResult.Wrong(
                expected = expected,
                tapped = tags.firstOrNull { it.tagId.equals(tagId, ignoreCase = true) }
            )
        }
    }

    private companion object {
        fun buildSequence(pool: List<NfcTag>, length: Int): List<NfcTag> {
            if (pool.isEmpty() || length <= 0) return emptyList()
            if (length <= pool.size) {
                // Enough tags for the whole sequence — unique, no repeats.
                return pool.shuffled().take(length)
            }
            // Not enough tags: repeat them, but never the same tag back-to-back.
            val result = ArrayList<NfcTag>(length)
            var lastId: String? = null
            repeat(length) {
                val candidates = pool.filter { it.tagId != lastId }
                    .ifEmpty { pool } // only when a single tag is registered
                val pick = candidates.random()
                result.add(pick)
                lastId = pick.tagId
            }
            return result
        }
    }
}

sealed interface CheckpointResult {
    /** Right tag; [next] is the new checkpoint to seek. */
    data class Correct(val next: NfcTag) : CheckpointResult

    /**
     * Wrong tag; [expected] is the one still being sought. [tapped] is the tag that
     * was actually scanned, or null if it isn't one of the registered tags.
     */
    data class Wrong(val expected: NfcTag, val tapped: NfcTag?) : CheckpointResult

    /** The final checkpoint was just tapped. */
    data object SequenceComplete : CheckpointResult

    /** A tag was scanned after the sequence already finished. */
    data object AlreadyComplete : CheckpointResult
}
