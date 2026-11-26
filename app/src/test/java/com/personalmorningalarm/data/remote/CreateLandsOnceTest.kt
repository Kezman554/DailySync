package com.personalmorningalarm.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.entity.ChalkboardVerb
import com.personalmorningalarm.data.entity.ShoppingVerb
import com.personalmorningalarm.data.model.ChalkboardList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/**
 * Every add lands once, over a real socket. The hub here does what vault-api does
 * with an Idempotency-Key: the first request with a key adds and remembers it with
 * (route, list, text); a repeat adds nothing and gets the first answer; the key on
 * other text is a 409. With no key, every call adds. And it can drop the connection
 * after it has added: the lost answer that, unkeyed, means a second copy.
 *
 * Each add first reads, so the POST goes out on a pooled connection: the case where
 * OkHttp resends by itself.
 */
@RunWith(RobolectricTestRunner::class)
class CreateLandsOnceTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val settings = AlfredSettings(context)
    private val server = MockWebServer()
    private val repository = AlfredRepository(settings, AlfredResponseCache(context))
    private lateinit var db: AppDatabase

    /** What the hub holds: one entry per add it made, as "route|list|text". */
    private val added: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The keys the hub remembers, each with the (route, list, text) it was first used for. */
    private val keys: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())

    /** Every add attempt that reached the hub, as (what, key or null). */
    private val attempts: MutableList<Pair<String, String?>> = Collections.synchronizedList(mutableListOf())

    /** This many add answers in a row are lost, after the hub has acted on the request. */
    @Volatile
    private var answersToLose = 0

    @Before
    fun setUp() {
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return MockResponse().setBody("[]")
                val path = request.requestUrl.encodedPath()
                val what = when {
                    path == "/chalkboard" -> "chalkboard|${request.requestUrl.queryParameter("list")}|${request.body.readUtf8()}"
                    path == "/inbox" -> "inbox|0-inbox|${request.body.readUtf8()}"
                    else -> "shopping|${path.removePrefix("/shopping/")}|${request.body.readUtf8()}"
                }
                val key = request.getHeader(AddKey.HEADER)
                attempts += what to key
                val first = key?.let { keys[it] }
                when {
                    key == null -> added += what
                    first == null -> { keys[key] = what; added += what }
                    first != what -> return MockResponse().setResponseCode(409).setBody("""{"detail":"key reused"}""")
                    else -> Unit // a repeat: nothing added, the first answer again
                }
                if (answersToLose > 0) {
                    answersToLose--
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
        context.deleteDatabase(FILE_DB)
        AlfredReachability.reset()
    }

    /** Every attempt reached the hub with the same, non-empty key. */
    private fun assertOneKeyOnEveryAttempt(minAttempts: Int = 2) {
        val sent = attempts.map { it.second }
        assertTrue("expected at least $minAttempts attempts, saw ${sent.size}", sent.size >= minAttempts)
        assertEquals(1, sent.toSet().size)
        assertTrue(!sent.first().isNullOrEmpty())
    }

    // --- a lost answer is sent again, under the same key, and lands once ---

    @Test
    fun `a to-do add whose answer is lost is resent and lands once`() = runBlocking {
        repository.getChalkboard(ChalkboardList.FAMILY)
        answersToLose = 1

        assertEquals(AlfredWriteResult.Done, repository.addChalkboardItem(ChalkboardList.FAMILY, "Oil the gate", AddKey.new()))

        assertEquals(listOf("chalkboard|family|{\"text\":\"Oil the gate\"}"), added)
        assertOneKeyOnEveryAttempt()
    }

    @Test
    fun `a shopping item add whose answer is lost is resent and lands once`() = runBlocking {
        repository.getShoppingLists()
        answersToLose = 1

        assertEquals(ShoppingWriteResult.Done, repository.addShoppingItem("invented-list.md", "Oat milk", AddKey.new()))

        assertEquals(1, added.size)
        assertOneKeyOnEveryAttempt()
    }

    @Test
    fun `a capture whose answer is lost is resent, lands once, and leaves nothing queued`() = runBlocking {
        val sync = InboxSync(repository, db.pendingInboxWriteDao())
        repository.getInbox()
        answersToLose = 1

        assertEquals(InboxWriteResult.Done, sync.capture("A passing thought"))

        assertEquals(listOf("inbox|0-inbox|A passing thought"), added)
        assertEquals(0, db.pendingInboxWriteDao().countPending())
        assertOneKeyOnEveryAttempt()
    }

    // --- answers lost until the add is queued: the queue replays the same key ---

    @Test
    fun `a to-do add that is queued after lost answers replays under its key and lands once`() = runBlocking {
        val sync = ChalkboardSync(repository, db.pendingChalkboardWriteDao())
        repository.getChalkboard(ChalkboardList.NICK)
        answersToLose = LOSE_EVERY_LIVE_ATTEMPT
        val key = AddKey.new()

        // What the To-do screen does: send live, and queue under the same key if it doesn't land.
        assertEquals(AlfredWriteResult.Unreachable, repository.addChalkboardItem(ChalkboardList.NICK, "Oil the gate", key))
        sync.enqueue(ChalkboardList.NICK, ChalkboardVerb.ADD, "Oil the gate", key = key)
        answersToLose = 0
        assertEquals(1, sync.flush().delivered)

        assertEquals(1, added.size)
        assertEquals(setOf(key), attempts.map { it.second }.toSet())
        assertOneKeyOnEveryAttempt(minAttempts = 3)
    }

    @Test
    fun `a shopping item add that is queued after lost answers replays under its key and lands once`() = runBlocking {
        val sync = ShoppingSync(repository, db.pendingShoppingWriteDao())
        repository.getShoppingLists()
        answersToLose = LOSE_EVERY_LIVE_ATTEMPT
        val key = AddKey.new()

        assertEquals(ShoppingWriteResult.Unreachable, repository.addShoppingItem("invented-list.md", "Oat milk", key))
        sync.enqueue("invented-list.md", ShoppingVerb.ADD, "Oat milk", key = key)
        answersToLose = 0
        assertEquals(1, sync.flush().delivered)

        assertEquals(1, added.size)
        assertEquals(setOf(key), attempts.map { it.second }.toSet())
        assertOneKeyOnEveryAttempt(minAttempts = 3)
    }

    @Test
    fun `a capture whose live answers are all lost stays queued, then replays under its key and lands once`() = runBlocking {
        val sync = InboxSync(repository, db.pendingInboxWriteDao())
        repository.getInbox()
        answersToLose = LOSE_EVERY_LIVE_ATTEMPT

        assertEquals(InboxWriteResult.Unreachable, sync.capture("A passing thought"))
        val queuedKey = db.pendingInboxWriteDao().getPending().single().idempotencyKey
        answersToLose = 0
        assertEquals(1, sync.flush().delivered)

        assertEquals(1, added.size)
        assertEquals(setOf(queuedKey), attempts.map { it.second }.toSet())
        assertOneKeyOnEveryAttempt(minAttempts = 3)
    }

    @Test
    fun `a queue replay whose answer is lost is sent again and lands once`() = runBlocking {
        val chalkboard = ChalkboardSync(repository, db.pendingChalkboardWriteDao())
        val shopping = ShoppingSync(repository, db.pendingShoppingWriteDao())
        val inbox = InboxSync(repository, db.pendingInboxWriteDao())
        chalkboard.enqueue(ChalkboardList.FAMILY, ChalkboardVerb.ADD, "Oil the gate")
        shopping.enqueue("invented-list.md", ShoppingVerb.ADD, "Oat milk")
        inbox.enqueue("Captured on the train")
        repository.getInbox()

        answersToLose = 1
        assertEquals(1, chalkboard.flush().delivered)
        answersToLose = 1
        assertEquals(1, shopping.flush().delivered)
        answersToLose = 1
        assertEquals(1, inbox.flush().delivered)

        assertEquals(3, added.size)
        // Each add went at least twice, and only ever under its own key.
        assertEquals(3, attempts.groupBy { it.first }.size)
        attempts.groupBy { it.first }.values.forEach { tries ->
            assertTrue(tries.size >= 2)
            assertEquals(1, tries.map { it.second }.toSet().size)
        }
    }

    // --- queued offline, app killed, restarted: the original key goes out ---

    @Test
    fun `adds queued offline keep their keys across the app being killed and restarted`() = runBlocking {
        settings.setHost("$LOCALHOST:${closedPort()}")
        val onDisk = fileDb()
        ChalkboardSync(repository, onDisk.pendingChalkboardWriteDao())
            .enqueue(ChalkboardList.FAMILY, ChalkboardVerb.ADD, "Oil the gate")
        ShoppingSync(repository, onDisk.pendingShoppingWriteDao())
            .enqueue("invented-list.md", ShoppingVerb.ADD, "Oat milk")
        assertEquals(InboxWriteResult.Unreachable, InboxSync(repository, onDisk.pendingInboxWriteDao()).capture("A passing thought"))
        val keysQueued = setOf(
            onDisk.pendingChalkboardWriteDao().getPending().single().idempotencyKey,
            onDisk.pendingShoppingWriteDao().getPending().single().idempotencyKey,
            onDisk.pendingInboxWriteDao().getPending().single().idempotencyKey
        )
        assertEquals(0, attempts.size)
        onDisk.close() // the app is killed

        settings.setHost("$LOCALHOST:${server.port}")
        val restarted = fileDb()
        try {
            assertEquals(1, ChalkboardSync(repository, restarted.pendingChalkboardWriteDao()).flush().delivered)
            assertEquals(1, ShoppingSync(repository, restarted.pendingShoppingWriteDao()).flush().delivered)
            assertEquals(1, InboxSync(repository, restarted.pendingInboxWriteDao()).flush().delivered)
        } finally {
            restarted.close()
        }

        assertEquals(3, added.size)
        assertEquals(keysQueued, attempts.map { it.second }.toSet())
        assertEquals(3, keysQueued.size)
    }

    // --- 409: the key reused for other text is a bug: failed, never retried ---

    @Test
    fun `a queued to-do add the hub refuses with 409 is failed and never sent again`() = runBlocking {
        val sync = ChalkboardSync(repository, db.pendingChalkboardWriteDao())
        keys["reused"] = "chalkboard|nick|{\"text\":\"Something else\"}"
        sync.enqueue(ChalkboardList.NICK, ChalkboardVerb.ADD, "Oil the gate", key = "reused")

        val outcome = sync.flush()

        assertEquals(1, outcome.conflicted)
        assertEquals(0, outcome.remaining)
        assertEquals(true, db.pendingChalkboardWriteDao().observeAll().first().single().failed)
        sync.flush()
        assertEquals(1, attempts.size)
        assertEquals(0, added.size)
    }

    @Test
    fun `a queued shopping add the hub refuses with 409 is failed and never sent again`() = runBlocking {
        val sync = ShoppingSync(repository, db.pendingShoppingWriteDao())
        keys["reused"] = "inbox|0-inbox|Something else"
        sync.enqueue("invented-list.md", ShoppingVerb.ADD, "Oat milk", key = "reused")

        assertEquals(1, sync.flush().conflicted)
        sync.flush()

        assertEquals(1, attempts.size)
        assertEquals(0, added.size)
    }

    @Test
    fun `a live add refused with 409 comes back refused, not unreachable, after one attempt`() = runBlocking {
        keys["reused"] = "inbox|0-inbox|Something else"

        assertEquals(AlfredWriteResult.Refused, repository.addChalkboardItem(ChalkboardList.NICK, "Oil the gate", "reused"))
        assertEquals(ShoppingWriteResult.Refused, repository.addShoppingItem("invented-list.md", "Oat milk", "reused"))
        assertEquals(InboxWriteResult.Rejected, repository.captureToInbox("A passing thought", "reused"))

        assertEquals(3, attempts.size)
        assertEquals(0, added.size)
    }

    @Test
    fun `a queued capture the hub refuses with 409 is failed and never sent again`() = runBlocking {
        val sync = InboxSync(repository, db.pendingInboxWriteDao())
        sync.enqueue("A passing thought")
        keys[db.pendingInboxWriteDao().getPending().single().idempotencyKey] = "inbox|0-inbox|Something else"

        assertEquals(1, sync.flush().refused)
        sync.flush()

        assertEquals(1, attempts.size)
        assertEquals(0, added.size)
    }

    // --- an add that never left the phone ---

    @Test
    fun `an add the hub never saw is not resent at once, and goes out once when it is back`() = runBlocking {
        val sync = ChalkboardSync(repository, db.pendingChalkboardWriteDao())
        settings.setHost("$LOCALHOST:${closedPort()}")
        val key = AddKey.new()

        assertEquals(AlfredWriteResult.Unreachable, repository.addChalkboardItem(ChalkboardList.NICK, "Oil the gate", key))
        sync.enqueue(ChalkboardList.NICK, ChalkboardVerb.ADD, "Oil the gate", key = key)
        settings.setHost("$LOCALHOST:${server.port}")
        assertEquals(1, sync.flush().delivered)

        assertEquals(listOf("chalkboard|nick|{\"text\":\"Oil the gate\"}" to key), attempts.toList())
    }

    private fun fileDb(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, FILE_DB)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val LOCALHOST = "127.0.0.1"
        const val FILE_DB = "create_lands_once.db"

        /** More lost answers than one live add makes attempts (OkHttp's retry and the repository's). */
        const val LOSE_EVERY_LIVE_ATTEMPT = 10
    }
}
