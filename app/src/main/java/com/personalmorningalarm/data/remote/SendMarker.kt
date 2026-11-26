package com.personalmorningalarm.data.remote

/**
 * Whether a request got as far as a connection. Attached as the request's tag and
 * set by [AlfredClient]'s network interceptor, which only runs once connected: if a
 * call fails with [sent] still false, nothing reached the hub and nothing was made.
 * True is "may have arrived", never proof that it did.
 */
class SendMarker {
    @Volatile
    var sent: Boolean = false
}
