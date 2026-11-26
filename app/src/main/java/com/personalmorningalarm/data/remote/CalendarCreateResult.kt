package com.personalmorningalarm.data.remote

import com.personalmorningalarm.data.model.CalendarEventDto

/**
 * How adding a Young Family event went. Nothing here is retried by anyone but the
 * person: POST /calendar/events makes a new event on every call.
 */
sealed interface CalendarCreateResult {

    /** The event is in. [event] is the hub's copy, null only if its answer couldn't be read. */
    data class Created(val event: CalendarEventDto?) : CalendarCreateResult

    /** The hub read the request and refused it (400/422). [reason] is its own words, if any. */
    data class Refused(val reason: String?) : CalendarCreateResult

    /** The hub couldn't be reached at all: nothing was sent, so trying again is safe. */
    data object NotSent : CalendarCreateResult

    /**
     * It went out but no answer came back (a timeout or a dropped connection), or
     * the hub answered with a fault of its own. The event may be in: check the
     * calendar before trying again.
     */
    data object Unconfirmed : CalendarCreateResult
}
