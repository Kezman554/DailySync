package com.personalmorningalarm.data.remote

import com.personalmorningalarm.data.model.PresenceStatusDto

/**
 * Outcome of a presence call to the dashboard. Unlike the Alfred results there is
 * no Stale case and no queued case: a cached "signed in" would be a claim about a
 * session that may have lapsed, and a queued sign-in is exactly what must never
 * happen (see [PresenceRepository]).
 */
sealed interface PresenceResult {

    /** The dashboard answered with the session's current state. */
    data class Status(val status: PresenceStatusDto) : PresenceResult

    /** Wrong PIN. Nothing opened — and nothing already open was closed. */
    data object WrongPin : PresenceResult

    /** Too many wrong PINs in a row; every attempt is refused for [retryAfterSeconds]. */
    data class LockedOut(val retryAfterSeconds: Int?) : PresenceResult

    /** The dashboard has no PIN configured, so no session can be opened at all. */
    data object NotConfigured : PresenceResult

    /** The dashboard answered with something unexpected. Nothing is retried. */
    data class Failed(val httpCode: Int) : PresenceResult

    /** The dashboard didn't answer. The attempt is over — it is not kept for later. */
    data object Unreachable : PresenceResult
}
