package com.personalmorningalarm.data.remote

import java.util.UUID

/**
 * The Idempotency-Key for one add: a to-do, a shopping item or an inbox capture.
 *
 * vault-api remembers each key it sees on POST /chalkboard, /shopping/{listId} and
 * /inbox for seven days. A second request with the same key, route, list and text
 * adds nothing and gets back the first answer, so an add whose answer was lost can
 * be sent again. The same key with other text is a 409: a phone bug, never retried.
 *
 * Made once, when the user makes the add, and stored with it: every attempt sends
 * the same key (OkHttp's own retry, the repository's, every queue replay).
 */
object AddKey {
    const val HEADER = "Idempotency-Key"

    fun new(): String = UUID.randomUUID().toString()
}
