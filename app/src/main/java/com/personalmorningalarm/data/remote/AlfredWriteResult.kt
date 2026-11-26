package com.personalmorningalarm.data.remote

import com.personalmorningalarm.data.model.ChalkboardTaskDto

/**
 * Outcome of a chalkboard write. Like [AlfredResult], nothing here throws —
 * Alfred being unreachable is ordinary, it just means the change didn't land.
 */
sealed interface AlfredWriteResult {

    /** The write landed. Callers refetch to reconcile rather than trusting local state. */
    data object Done : AlfredWriteResult

    /**
     * The targeting line no longer matches the vault (edited elsewhere, or swept).
     * [current] is the list as it stands now, returned in the same round trip.
     */
    data class StaleTarget(val current: List<ChalkboardTaskDto>) : AlfredWriteResult

    /** Alfred didn't take the write — unreachable, or an unexpected error. */
    data object Unreachable : AlfredWriteResult

    /**
     * An add's 409: its Idempotency-Key was already used for other text. A phone
     * bug, not a vault change: nothing was added, it's logged, shown as a failed
     * change, and never retried.
     */
    data object Refused : AlfredWriteResult
}
