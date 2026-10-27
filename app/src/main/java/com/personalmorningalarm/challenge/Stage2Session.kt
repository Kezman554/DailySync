package com.personalmorningalarm.challenge

/**
 * Stage 2 progress, kept for the life of the process rather than the life of the
 * screen showing it. The dismissal activity can be destroyed mid-morning (the
 * system trims its task when something else, like a stray NFC popup, takes the
 * foreground); the countdown service keeps running regardless, so the next
 * activity picks the sequence up from here instead of starting over or being lost.
 */
object Stage2Session {

    class Progress(
        val checkpoints: NfcCheckpointManager?,
        val content: ContentScreenManager?,
        val stretchMinutes: Int,
        val startMs: Long,
        val alarmStartMs: Long
    ) {
        /** Content screens already shown; advances as each checkpoint is tapped. */
        @Volatile
        var gapsShown: Int = 0
    }

    @Volatile
    var current: Progress? = null
        private set

    fun begin(progress: Progress) {
        current = progress
    }

    fun clear() {
        current = null
    }
}
