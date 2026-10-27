package com.personalmorningalarm.challenge

/**
 * Where the nuclear gauntlet stands, kept for the life of the process rather than
 * the life of the screen showing it. The alarm itself runs in a service, so the
 * screen can be destroyed under it (the system trims tasks, the user swipes it
 * away); a rebuilt screen picks up the challenges already done and the same
 * maths problem instead of resetting them. A process kill loses it, which is the
 * fair outcome: the alarm restarts and the gauntlet begins again.
 */
class NuclearSession(val math: MathChallenge = MathChallenge()) {
    @Volatile var shakeDone = false
    @Volatile var mathDone = false
    @Volatile var typeDone = false

    val allDone: Boolean get() = shakeDone && mathDone && typeDone

    companion object {
        @Volatile
        private var current: NuclearSession? = null

        /** The gauntlet in progress, or a fresh one if none is. */
        @Synchronized
        fun obtain(): NuclearSession = current ?: NuclearSession().also { current = it }

        /** Forget it: the alarm has ended, or a new one is starting. */
        @Synchronized
        fun clear() {
            current = null
        }
    }
}
