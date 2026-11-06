package com.personalmorningalarm.data.remote

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Reads give up at 3s and writes wait 15s, over a real socket: a write the phone
 * abandons still finishes on the Pi, so giving up early is what duplicates it.
 */
@RunWith(RobolectricTestRunner::class)
class AlfredClientTimeoutTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val settings = AlfredSettings(context)
    private val server = MockWebServer()
    private val repository = AlfredRepository(settings, AlfredResponseCache(context))

    private var answerAfterSeconds = 0L

    @Before
    fun setUp() {
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setHeadersDelay(answerAfterSeconds, TimeUnit.SECONDS)
                    .setBody(if (request.method == "GET") "[]" else "{}")
        })
        server.start(InetAddress.getByName(LOCALHOST), 0)
        settings.setHost("$LOCALHOST:${server.port}")
    }

    @After
    fun tearDown() {
        server.shutdown()
        AlfredReachability.reset()
    }

    /** The timeouts OkHttp really applies to [request], interceptors included. */
    private fun appliedTimeouts(request: Request): Pair<Int, Int> {
        var applied = 0 to 0
        val client = AlfredClient.http.newBuilder()
            .addNetworkInterceptor { chain ->
                applied = chain.connectTimeoutMillis() to chain.readTimeoutMillis()
                chain.proceed(chain.request())
            }
            .build()
        client.newCall(request).execute().close()
        return applied
    }

    @Test
    fun `a write waits 15 seconds to be answered but still connects within 3`() {
        val write = Request.Builder().url(server.url("/chalkboard")).post(RequestBody.create(null, "{}")).build()

        assertEquals(3_000 to 15_000, appliedTimeouts(write))
    }

    @Test
    fun `a delete gets the same timeouts as any other write`() {
        val delete = Request.Builder().url(server.url("/shopping/garden-tools.md")).delete().build()

        assertEquals(3_000 to 15_000, appliedTimeouts(delete))
    }

    @Test
    fun `a read keeps its 3 seconds for both`() {
        val read = Request.Builder().url(server.url("/chalkboard")).build()

        assertEquals(3_000 to 3_000, appliedTimeouts(read))
    }

    @Test
    fun `every kind of write answered after 5 seconds lands once`() = runBlocking {
        answerAfterSeconds = 5

        val results = listOf(
            async { repository.addChalkboardItem("Oil the gate") },
            async { repository.tickChalkboardItem("- [ ] Oil the gate") },
            async { repository.dropChalkboardItem("- [ ] Oil the gate") },
            async { repository.addShoppingItem("groceries", "Oat milk") },
            async { repository.captureToInbox("A passing thought") }
        ).awaitAll()

        assertEquals(
            listOf(
                AlfredWriteResult.Done, AlfredWriteResult.Done, AlfredWriteResult.Done,
                ShoppingWriteResult.Done, InboxWriteResult.Done
            ),
            results
        )
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `a delete answered after 5 seconds lands once`() = runBlocking {
        answerAfterSeconds = 5

        val result = repository.deleteShoppingList("garden-tools.md")

        assertTrue(result is ShoppingDeleteResult.Deleted)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a read answered after 5 seconds is still given up on at 3`() = runBlocking {
        answerAfterSeconds = 5

        val started = System.currentTimeMillis()
        val result = repository.getChalkboard()
        val waited = System.currentTimeMillis() - started

        assertEquals(AlfredResult.Unavailable, result)
        assertTrue("gave up after ${waited}ms", waited in 2_500..4_500)
    }

    @Test
    fun `a write that cannot connect comes back unreachable at once`() = runBlocking {
        settings.setHost("$LOCALHOST:${closedPort()}")

        val started = System.currentTimeMillis()
        val result = repository.addChalkboardItem("Oil the gate")

        assertEquals(AlfredWriteResult.Unreachable, result)
        assertTrue(System.currentTimeMillis() - started < 3_000)
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val LOCALHOST = "127.0.0.1"
    }
}
