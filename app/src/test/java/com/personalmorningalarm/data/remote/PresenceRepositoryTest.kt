package com.personalmorningalarm.data.remote

import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.model.PresenceStatusDto
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response
import java.io.IOException

/**
 * Every dashboard answer maps to a result the tile can say out loud, and an
 * unreachable dashboard is a plain failure — never cached, never queued.
 */
@RunWith(RobolectricTestRunner::class)
class PresenceRepositoryTest {

    private val settings =
        DashboardSettings(ApplicationProvider.getApplicationContext<android.app.Application>())

    private val open = PresenceStatusDto(open = true, person = "alex", expiresInSeconds = 300.0)
    private val closed = PresenceStatusDto(open = false)

    /** Every PIN the fake dashboard was sent, in order. */
    private val pinsSent = mutableListOf<String>()
    private var calls = 0

    private fun repository(answer: () -> Response<PresenceStatusDto>) = PresenceRepository(
        settings,
        serviceProvider = {
            object : DashboardApiService {
                override suspend fun getPresence(): Response<PresenceStatusDto> { calls++; return answer() }
                override suspend fun openPresence(body: PresencePinRequest): Response<PresenceStatusDto> {
                    pinsSent += body.pin
                    calls++
                    return answer()
                }
                override suspend fun closePresence(): Response<PresenceStatusDto> { calls++; return answer() }
            }
        }
    )

    private fun error(code: Int, headers: Headers = Headers.of()): Response<PresenceStatusDto> =
        Response.error(
            ResponseBody.create(MediaType.parse("application/json"), """{"detail":"x"}"""),
            okhttp3.Response.Builder()
                .code(code)
                .message("x")
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .headers(headers)
                .request(okhttp3.Request.Builder().url("http://localhost/").build())
                .build()
        )

    @Test
    fun `a right PIN opens and returns the session`() = runBlocking {
        val result = repository { Response.success(open) }.open("1234")

        assertEquals(PresenceResult.Status(open), result)
        assertEquals(listOf("1234"), pinsSent)
    }

    @Test
    fun `a wrong PIN is a refusal, not a failure to reach`() = runBlocking {
        assertEquals(PresenceResult.WrongPin, repository { error(401) }.open("0000"))
    }

    @Test
    fun `a lockout carries the dashboard's Retry-After`() = runBlocking {
        val result = repository { error(429, Headers.of("Retry-After", "42")) }.open("0000")
        assertEquals(PresenceResult.LockedOut(42), result)
    }

    @Test
    fun `a dashboard with no PIN set says so`() = runBlocking {
        assertEquals(PresenceResult.NotConfigured, repository { error(503) }.open("1234"))
    }

    @Test
    fun `anything else unexpected is a failure with its code`() = runBlocking {
        assertEquals(PresenceResult.Failed(500), repository { error(500) }.open("1234"))
    }

    @Test
    fun `an unreachable dashboard fails the sign-in once and keeps nothing`() = runBlocking {
        val repo = repository { throw IOException("Host unreachable") }

        assertEquals(PresenceResult.Unreachable, repo.open("1234"))
        // Exactly one attempt. There's no queue to flush and nothing to replay —
        // a later status read sends no PIN.
        assertEquals(PresenceResult.Unreachable, repo.status())
        assertEquals(listOf("1234"), pinsSent)
        assertEquals(2, calls)
    }

    @Test
    fun `status is never served from a cache`() = runBlocking {
        var reachable = true
        val repo = repository { if (reachable) Response.success(open) else throw IOException("gone") }

        assertEquals(PresenceResult.Status(open), repo.status())
        reachable = false
        // The Alfred tiles would serve a stale copy here. A stale "signed in" is
        // a lie about a session that may have lapsed, so this one doesn't.
        assertEquals(PresenceResult.Unreachable, repo.status())
    }

    @Test
    fun `close reports the closed state`() = runBlocking {
        val result = repository { Response.success(closed) }.close()
        assertEquals(PresenceResult.Status(closed), result)
    }

    @Test
    fun `the PIN request never prints the PIN`() {
        val printed = PresencePinRequest("8642").toString()
        assertFalse(printed.contains("8642"))
        assertTrue(printed.contains("redacted"))
    }
}
