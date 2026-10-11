package com.personalmorningalarm.data.remote

import com.personalmorningalarm.data.model.PresenceStatusDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * The kitchen dashboard's API — a different service from [AlfredApiService], on
 * its own address ([DashboardSettings]). Only the presence session so far.
 *
 * Every presence route answers the same status shape. They all return [Response]
 * so a refusal (401 wrong PIN, 429 locked out, 503 no PIN configured) reaches
 * [PresenceRepository] as data rather than an exception.
 */
interface DashboardApiService {

    /** Whether a session is open. A pure read on the dashboard — it never extends one. */
    @GET("api/presence")
    suspend fun getPresence(): Response<PresenceStatusDto>

    /** Opens a session if [body]'s PIN is right. The PIN goes in the body, never the URL. */
    @POST("api/presence/open")
    suspend fun openPresence(@Body body: PresencePinRequest): Response<PresenceStatusDto>

    /** Ends the session. Idempotent: closing a closed session is fine. */
    @POST("api/presence/close")
    suspend fun closePresence(): Response<PresenceStatusDto>
}

/** Body for POST /api/presence/open. Built per attempt and never kept. */
data class PresencePinRequest(val pin: String) {
    // The generated toString would print the PIN into any log line or crash
    // report that happened to include the request.
    override fun toString(): String = "PresencePinRequest(pin=<redacted>)"
}
