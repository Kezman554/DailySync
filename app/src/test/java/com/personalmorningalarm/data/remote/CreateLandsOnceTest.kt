package com.personalmorningalarm.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.model.ChalkboardList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.ServerSocket
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections

/**
 * Every create lands once, over a real socket. The hub here keeps what each POST
 * makes, as vault-api does (a line, an item, a file), and can drop the connection
 * after it has made it: the lost answer that used to mean a second copy, from
 * OkHttp's quiet resend or the queue's replay.
 *
 * Each create first reads, so the POST goes out on a pooled connection: the case
 * where OkHttp resends.
 */
@RunWith(RobolectricTestRunner::class)
class CreateLandsOnceTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val settings = AlfredSettings(context)
    private val server = MockWebServer()
    private val repository = AlfredRepository(settings, AlfredResponseCache(context))
    private lateinit var db: AppDatabase

    /** What the hub holds: each POST that reached it adds one. */
    private val chalkboardLines: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val shoppingLines: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val inboxFiles: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    /** The next create is made, then its answer lost. */
    @Volatile
    private var loseNextAnswer = false

    /** The phone's clock, as InboxSync sees it: real time plus this. */
    @Volatile
    private var clockOffset = 0L

    @Before
    fun setUp() {
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl.encodedPath()
                val body = request.body.readUtf8()
                if (request.method == "GET") {
                    return MockResponse().setBody(
                        when {
                            path == "/inbox" -> inboxJson()
                            else -> "[]"
                        }
                    )
                }
                when {
                    path == "/chalkboard" -> chalkboardLines += body
                    path == "/inbox" -> inboxFiles += inboxFilename(body) to body.trim() + "\n"
                    path.startsWith("/shopping/") -> shoppingLines += body
                }
                if (loseNextAnswer) {
                    loseNextAnswer = false
                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                }
                return MockResponse().setResponseCode(201).setBody("{}")
            }
        })
        server.start(InetAddress.getByName(LOCALHOST), 0)
        settings.setHost("$LOCALHOST:${server.port}")
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        AlfredReachability.reset()
    }

    /** Named as the hub names one: YYYY-MM-DD-HHMM-slug, in its time, -2 for a repeat. */
    private fun inboxFilename(text: String): String {
        val stem = LocalDateTime.now(ZoneId.of("Europe/London")).format(STAMP) + "-capture"
        var name = "$stem.md"
        var n = 1
        while (inboxFiles.any { it.first == name }) name = "$stem-${++n}.md"
        return name
    }

    private fun inboxJson(): String = inboxFiles.reversed().joinToString(",", "[", "]") { (name, content) ->
        """{"filename":"$name","content":${com.google.gson.Gson().toJson(content)}}"""
    }

    private fun inboxSync() = InboxSync(repository, db.pendingInboxWriteDao()) { System.currentTimeMillis() + clockOffset }

    // --- OkHttp never resends a create ---

    @Test
    fun `a to-do add whose answer is lost makes one line`() = runBlocking {
        repository.getChalkboard(ChalkboardList.FAMILY)
        loseNextAnswer = true

        assertEquals(AlfredWriteResult.Unreachable, repository.addChalkboardItem(ChalkboardList.FAMILY, "Oil the gate"))

        assertEquals(1, chalkboardLines.size)
    }

    @Test
    fun `a shopping item add whose answer is lost makes one item`() = runBlocking {
        repository.getShoppingLists()
        loseNextAnswer = true

        assertEquals(ShoppingWriteResult.Unreachable, repository.addShoppingItem("invented-list.md", "Oat milk"))

        assertEquals(1, shoppingLines.size)
    }

    @Test
    fun `an inbox capture whose answer is lost makes one file, and says it went out`() = runBlocking {
        repository.getInbox()
        loseNextAnswer = true
        val sent = SendMarker()

        assertEquals(InboxWriteResult.Unreachable, repository.captureToInbox("A passing thought", sent))

        assertEquals(1, inboxFiles.size)
        assertEquals(true, sent.sent)
    }

    // --- the inbox queue never sends again what may have landed ---

    @Test
    fun `a live capture whose answer is lost is found on the hub, not sent again`() = runBlocking {
        val sync = inboxSync()
        repository.getInbox()
        loseNextAnswer = true

        assertEquals(InboxWriteResult.Unreachable, sync.capture("A passing thought"))
        // Queued, knowing it may have landed.
        val row = db.pendingInboxWriteDao().getPending().single()
        assertEquals(true, row.attemptedAt != null)

        clockOffset = InboxSync.SETTLE_MILLIS + 1_000
        val outcome = sync.flush()

        assertEquals(1, outcome.delivered)
        assertEquals(0, outcome.remaining)
        assertEquals(1, inboxFiles.size)
    }

    @Test
    fun `a queued capture whose replay answer is lost is found next time, not sent again`() = runBlocking {
        val sync = inboxSync()
        sync.enqueue("Captured on the train")
        repository.getInbox()
        loseNextAnswer = true

        assertEquals(1, sync.flush().remaining)
        assertEquals(1, inboxFiles.size)

        clockOffset = InboxSync.SETTLE_MILLIS + 1_000
        assertEquals(1, sync.flush().delivered)
        assertEquals(1, inboxFiles.size)
    }

    @Test
    fun `just after a lost answer, a capture not on the hub yet waits rather than going again`() = runBlocking {
        val sync = inboxSync()
        repository.getInbox()
        loseNextAnswer = true
        sync.capture("A passing thought")
        // The Pi hasn't finished writing it (it may still be queued behind others).
        inboxFiles.clear()

        val outcome = sync.flush()

        assertEquals(1, outcome.remaining)
        assertEquals(0, inboxFiles.size)
    }

    @Test
    fun `long after a lost answer, a capture that never landed is sent again, once`() = runBlocking {
        val sync = inboxSync()
        repository.getInbox()
        loseNextAnswer = true
        sync.capture("A passing thought")
        inboxFiles.clear() // it never got written

        clockOffset = InboxSync.SETTLE_MILLIS + 1_000
        assertEquals(1, sync.flush().delivered)

        assertEquals(1, inboxFiles.size)
    }

    @Test
    fun `a capture that never left the phone is sent without looking first`() = runBlocking {
        val sync = inboxSync()
        settings.setHost("$LOCALHOST:${closedPort()}")

        assertEquals(InboxWriteResult.Unreachable, sync.capture("A passing thought"))
        assertNull(db.pendingInboxWriteDao().getPending().single().attemptedAt)

        settings.setHost("$LOCALHOST:${server.port}")
        assertEquals(1, sync.flush().delivered)
        assertEquals(1, inboxFiles.size)
        assertEquals(listOf("POST /inbox"), requests())
    }

    @Test
    fun `two identical captures that both landed are two files, each claimed once`() = runBlocking {
        val sync = inboxSync()
        repository.getInbox()
        loseNextAnswer = true
        sync.capture("Milk")
        loseNextAnswer = true
        sync.capture("Milk")
        assertEquals(2, inboxFiles.size)

        clockOffset = InboxSync.SETTLE_MILLIS + 1_000
        assertEquals(2, sync.flush().delivered)

        assertEquals(2, inboxFiles.size)
    }

    /** Every request the hub saw since the last call, as "METHOD /path". */
    private fun requests(): List<String> {
        val seen = mutableListOf<String>()
        while (true) {
            val request = server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
            seen += "${request.method} ${request.requestUrl.encodedPath()}"
        }
        return seen
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val LOCALHOST = "127.0.0.1"
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
    }
}
