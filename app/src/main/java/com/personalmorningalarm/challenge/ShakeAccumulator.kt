package com.personalmorningalarm.challenge

/**
 * Pure, framework-free core of the Stage 1 shake challenge: turns a stream of
 * accelerometer force samples into cumulative "actively shaking" time and a
 * 0..1 progress value. Split out from [ShakeChallenge] so the timing/threshold
 * logic can be unit-tested with an injectable clock (the sensor plumbing that
 * needs a [android.content.Context] stays in [ShakeChallenge]).
 *
 * A sample counts toward the target only while shaking: its acceleration must
 * have deviated from gravity by more than [shakeThreshold] within the last
 * [pauseToleranceMs], which bridges the brief low-motion instant at each shake
 * reversal so continuous shaking accrues smoothly. Each counted interval is
 * capped at [maxIntervalMs] so a stalled/throttled sensor can't jump the timer.
 */
class ShakeAccumulator(
    private val targetDurationMs: Long,
    private val shakeThreshold: Float,
    private val pauseToleranceMs: Long = DEFAULT_PAUSE_TOLERANCE_MS,
    private val maxIntervalMs: Long = DEFAULT_MAX_INTERVAL_MS
) {
    private var lastSampleTime = 0L
    private var haveBaseline = false
    private var lastShakeTime = 0L
    private var haveShaken = false

    var accumulatedShakeMs = 0L
        private set

    var completed = false
        private set

    /** True once [accumulatedShakeMs] has reached the target. */
    val isComplete: Boolean get() = accumulatedShakeMs >= targetDurationMs

    /** Current progress toward the target, clamped to 0..1. */
    val progress: Float
        get() = (accumulatedShakeMs.toFloat() / targetDurationMs).coerceIn(0f, 1f)

    /**
     * Re-baselines the timing so a stop/start pause (e.g. switching shaking
     * arms) doesn't wrongly accrue time across the gap. Accumulated progress is
     * preserved — call [reset] to clear it.
     */
    fun rebaseline() {
        haveBaseline = false
        haveShaken = false
        lastSampleTime = 0L
        lastShakeTime = 0L
    }

    /** Clears accumulated progress so the challenge can run from scratch. */
    fun reset() {
        completed = false
        accumulatedShakeMs = 0L
        rebaseline()
    }

    /**
     * Feed one sample: [linearAccel] is |acceleration magnitude - gravity|,
     * [now] a monotonic-ish millisecond timestamp. Returns the new [progress].
     * The first sample after a rebaseline only establishes the timing baseline.
     */
    fun onSample(linearAccel: Float, now: Long): Float {
        if (!haveBaseline) {
            lastSampleTime = now
            haveBaseline = true
            return progress
        }

        if (linearAccel > shakeThreshold) {
            lastShakeTime = now
            haveShaken = true
        }

        // Count this interval only if a strong shake landed within the tolerance window.
        // E.g. at ~50Hz a shake peaks above the threshold a few times a second; the
        // samples between peaks (the arm turning round) fall below it, but still
        // count because a peak landed less than 500ms ago. Stop shaking and time
        // stops accruing half a second later. Each interval is the gap since the
        // previous sample, so progress tracks real time whatever the sensor's rate.
        val activelyShaking = haveShaken && (now - lastShakeTime <= pauseToleranceMs)
        if (activelyShaking) {
            val interval = (now - lastSampleTime).coerceAtMost(maxIntervalMs)
            accumulatedShakeMs += interval
        }
        lastSampleTime = now

        if (accumulatedShakeMs >= targetDurationMs) {
            completed = true
        }
        return progress
    }

    companion object {
        /**
         * How long after the last strong shake we still count as "shaking".
         * Wide enough to bridge the low-motion instant between shake reversals
         * so continuous shaking accrues smoothly.
         */
        const val DEFAULT_PAUSE_TOLERANCE_MS = 500L

        /** Caps a single interval so a stalled sensor can't jump the timer. */
        const val DEFAULT_MAX_INTERVAL_MS = 100L
    }
}
