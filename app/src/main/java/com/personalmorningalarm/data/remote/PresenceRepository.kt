package com.personalmorningalarm.data.remote

import android.content.Context
import android.util.Log
import com.personalmorningalarm.data.model.PresenceStatusDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response

/**
 * Opens, reads and closes the kitchen screen's presence session on the dashboard.
 *
 * LIVE OR NOTHING. This is deliberately NOT built like [AlfredRepository]: no
 * response cache, and no store-and-forward queue like [ChalkboardSync],
 * [InboxSync] or [ShoppingSync]. A sign-in queued while the Pi was unreachable
 * would flush when the phone next reconnected and open a personal board on the
 * kitchen wall hours later, with nobody standing there. So every call here is a
 * single attempt made now; if the dashboard doesn't answer, the result says so
 * and the attempt is gone.
 *
 * THE PIN PASSES THROUGH. [open] takes it as an argument, puts it in the request
 * body and keeps no reference — nothing here stores it, caches it or logs it.
 */
class PresenceRepository(
    private val settings: DashboardSettings,
    private val serviceProvider: (DashboardSettings) -> DashboardApiService = DashboardClient::service
) {

    constructor(context: Context) : this(DashboardSettings(context))

    /** Whether a session is open right now — read fresh every time, never cached. */
    suspend fun status(): PresenceResult = call("status") { it.getPresence() }

    /** One attempt to open a session with [pin]. */
    suspend fun open(pin: String): PresenceResult =
        call("open") { it.openPresence(PresencePinRequest(pin)) }

    /** Ends the session. Closing one that's already closed is fine. */
    suspend fun close(): PresenceResult = call("close") { it.closePresence() }

    private suspend fun call(
        what: String,
        request: suspend (DashboardApiService) -> Response<PresenceStatusDto>
    ): PresenceResult = withContext(Dispatchers.IO) {
        try {
            val response = request(serviceProvider(settings))
            val body = response.body()
            when {
                response.isSuccessful && body != null -> PresenceResult.Status(body)
                response.code() == 401 -> PresenceResult.WrongPin
                response.code() == 429 -> PresenceResult.LockedOut(
                    response.headers()["Retry-After"]?.trim()?.toIntOrNull()
                )
                response.code() == 503 -> PresenceResult.NotConfigured
                else -> {
                    Log.w(TAG, "Presence $what failed: HTTP ${response.code()}")
                    PresenceResult.Failed(response.code())
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.d(TAG, "Dashboard unreachable for presence $what (${e.javaClass.simpleName})")
            PresenceResult.Unreachable
        }
    }

    private companion object {
        const val TAG = "PMA"
    }
}
