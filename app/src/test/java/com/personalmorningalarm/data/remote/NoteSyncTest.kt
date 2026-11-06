package com.personalmorningalarm.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.entity.NoteVerb
import com.personalmorningalarm.data.entity.PendingNoteWrite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The note queue's contract, against a stand-in for vault-api over real HTTP. */
@RunWith(RobolectricTestRunner::class)
class NoteSyncTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val settings = AlfredSettings(context)
    private val cache = AlfredResponseCache(context)
    private val server = FakeNotesServer()

    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.pendingNoteWriteDao()
    private val alfred = AlfredRepository(settings, cache)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sync = NoteSync(alfred, dao, scope)

    @Before
    fun setUp() {
        server.start(settings)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        db.close()
        AlfredReachability.reset()
    }

    private suspend fun rows(): List<PendingNoteWrite> = sync.queue.first()

    /** Holds the next write at the server; call [release] to let it finish. */
    private inner class HeldWrite {
        private val gate = CountDownLatch(1)
        private val arrived = CountDownLatch(1)

        init {
            server.writeGate = gate
            server.writeArrived = arrived
        }

        fun awaitArrival() = assertTrue(arrived.await(5, TimeUnit.SECONDS))

        fun release() {
            server.writeGate = null
            server.writeArrived = null
            gate.countDown()
        }
    }

    // ---- Latest save wins ----

    @Test
    fun `only the latest save per note stays queued`() = runBlocking {
        server.down = true

        sync.save(SHED, "First thought").join()
        sync.save(SHED, "Second thought").join()
        sync.save(SHED, "Third thought").join()

        assertEquals(listOf("Third thought"), rows().map { it.text })
    }

    @Test
    fun `an offline note reaches the vault once with its last text`() = runBlocking {
        server.down = true
        sync.save(SHED, "First thought").join()
        sync.save(SHED, "Second thought").join()
        server.down = false

        val outcome = sync.flush()

        assertEquals(1, outcome.delivered)
        assertEquals(listOf("PUT $SHED"), server.writes.toList().takeLast(1))
        assertEquals(1, server.commits)
        assertEquals("Second thought", server.notes[SHED])
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `a new save replaces a refused one and gets another go`() = runBlocking {
        val tooLong = "x".repeat(FakeNotesServer.MAX_CHARS + 1)
        sync.save(SHED, tooLong).join()
        assertTrue(rows().single().failed)

        sync.save(SHED, "Shorter now").join()

        assertTrue(rows().isEmpty())
        assertEquals("Shorter now", server.notes[SHED])
    }

    @Test
    fun `the app's writes start one at a time, in the order they were made`() = runBlocking {
        val appScope = NoteSync.defaultScope()
        val started = Collections.synchronizedList(mutableListOf<String>())
        val firstRunning = CountDownLatch(1)
        val letFirstFinish = CountDownLatch(1)

        appScope.launch {
            started += "first"
            firstRunning.countDown()
            letFirstFinish.await(5, TimeUnit.SECONDS)
        }
        val second = appScope.launch { started += "second" }
        assertTrue(firstRunning.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)

        assertEquals("nothing overtakes a write in progress", listOf("first"), started.toList())
        letFirstFinish.countDown()
        second.join()
        assertEquals(listOf("first", "second"), started.toList())
        appScope.cancel()
    }

    // ---- Delete replaces a save ----

    @Test
    fun `a delete replaces a queued save`() = runBlocking {
        server.down = true
        sync.save(SHED, "Never mind").join()

        sync.delete(SHED).join()

        assertEquals(listOf(NoteVerb.DELETE.name), rows().map { it.verb })
    }

    @Test
    fun `a replaced save is never sent`() = runBlocking {
        server.down = true
        sync.save(SHED, "Never mind").join()
        sync.delete(SHED).join()
        server.writes.clear()
        server.down = false

        sync.flush()

        assertEquals(listOf("DELETE $SHED"), server.writes.toList())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `a save after a delete replaces the delete`() = runBlocking {
        server.down = true
        sync.delete(SHED).join()
        sync.save(SHED, "Changed my mind").join()
        server.down = false

        sync.flush()

        assertEquals("Changed my mind", server.notes[SHED])
    }

    // ---- Replay is safe twice ----

    @Test
    fun `a save whose answer was lost replays harmlessly`() = runBlocking {
        server.loseAnswers = true
        sync.save(SHED, "Landed anyway").join()
        assertEquals("the phone can't know it landed", 1, rows().size)
        server.loseAnswers = false

        sync.flush()

        assertTrue(rows().isEmpty())
        assertEquals(mapOf(SHED to "Landed anyway"), server.notes.toMap())
        assertEquals(1, server.commits)
    }

    @Test
    fun `a delete whose answer was lost replays harmlessly`() = runBlocking {
        server.notes[SHED] = "Old note"
        server.loseAnswers = true
        sync.delete(SHED).join()
        assertEquals(1, rows().size)
        server.loseAnswers = false

        sync.flush()

        assertTrue(rows().isEmpty())
        assertTrue(server.notes.isEmpty())
        assertEquals(1, server.commits)
    }

    @Test
    fun `flushing twice sends nothing the second time`() = runBlocking {
        sync.save(SHED, "Once only").join()
        val sent = server.writes.size

        val second = sync.flush()

        assertEquals(sent, server.writes.size)
        assertEquals(0, second.delivered)
        assertFalse(second.changedAnything)
    }

    @Test
    fun `deleting a note that is already gone counts as done`() = runBlocking {
        sync.delete(SHED).join()

        assertTrue(rows().isEmpty())
        assertEquals(0, server.commits)
    }

    // ---- A write made during a replay ----

    @Test
    fun `a save made while the previous one is with Alfred survives it`() = runBlocking {
        val held = HeldWrite()
        val first = sync.save(SHED, "Older text")
        held.awaitArrival()

        // The row being replayed is replaced under it, as an edit on the phone would.
        dao.put(PendingNoteWrite(noteId = SHED, verb = NoteVerb.SAVE.name, text = "Newer text", createdAt = 0))
        held.release()
        first.join()
        sync.flush()

        assertEquals("Newer text", server.notes[SHED])
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `a delete made while a save is with Alfred wins`() = runBlocking {
        val held = HeldWrite()
        val first = sync.save(SHED, "About to go")
        held.awaitArrival()

        dao.put(PendingNoteWrite(noteId = SHED, verb = NoteVerb.DELETE.name, text = "", createdAt = 0))
        held.release()
        first.join()
        sync.flush()

        assertTrue(server.notes.isEmpty())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `a note deleted while the queue is replaying is never saved first`() = runBlocking {
        server.down = true
        sync.save(SHED, "First in line").join()
        sync.save(ATTIC, "Deleted before its turn").join()
        server.down = false
        val held = HeldWrite()
        val flush = async(Dispatchers.IO) { sync.flush() }
        held.awaitArrival()

        dao.put(PendingNoteWrite(noteId = ATTIC, verb = NoteVerb.DELETE.name, text = "", createdAt = 0))
        held.release()
        flush.await()

        assertFalse(server.writes.contains("PUT $ATTIC"))
        assertTrue(server.writes.contains("DELETE $ATTIC"))
    }

    // ---- What stops a replay, and what doesn't ----

    @Test
    fun `an unreachable Alfred keeps the whole queue`() = runBlocking {
        server.down = true
        sync.save(SHED, "One").join()
        sync.save(ATTIC, "Two").join()

        val outcome = sync.flush()

        assertEquals(2, outcome.remaining)
        assertEquals(listOf("One", "Two"), rows().map { it.text })
        assertTrue(rows().none { it.failed })
    }

    @Test
    fun `notes replay in the order they were written`() = runBlocking {
        server.down = true
        sync.save(SHED, "One").join()
        sync.save(ATTIC, "Two").join()
        sync.save(SHED, "One again").join()
        server.down = false

        sync.flush()

        assertEquals(listOf("PUT $ATTIC", "PUT $SHED"), server.writes.toList())
    }

    @Test
    fun `refused text is kept, not retried, and doesn't hold up the rest`() = runBlocking {
        val tooLong = "x".repeat(FakeNotesServer.MAX_CHARS + 1)
        server.down = true
        sync.save(SHED, tooLong).join()
        sync.save(ATTIC, "Fine").join()
        server.down = false

        val outcome = sync.flush()
        val sent = server.writes.size
        sync.flush()

        assertEquals(1, outcome.delivered)
        assertEquals(1, outcome.refused)
        assertEquals(tooLong, rows().single().text)
        assertTrue(rows().single().failed)
        assertEquals("Fine", server.notes[ATTIC])
        assertEquals("a refused note isn't sent again", sent, server.writes.size)
        assertFalse(sync.hasPending())
    }

    @Test
    fun `anything other than a 422 stays queued`() = runBlocking {
        for (status in listOf(404, 500, 503)) {
            server.writeStatus = status

            sync.save(SHED, "Still here").join()

            assertEquals(status.toString(), 1, rows().size)
            assertFalse(status.toString(), rows().single().failed)
        }
    }

    @Test
    fun `a verb this build doesn't know fails rather than stalling the queue`() = runBlocking {
        server.down = true
        dao.put(PendingNoteWrite(noteId = SHED, verb = "ARCHIVE", text = "?", createdAt = 0))
        sync.save(ATTIC, "Fine").join()
        server.down = false

        sync.flush()

        assertTrue(rows().single().failed)
        assertEquals("Fine", server.notes[ATTIC])
    }

    @Test
    fun `the queue outlives the process`() = runBlocking {
        server.down = true
        sync.save(SHED, "Force closed").join()
        server.down = false

        NoteSync(alfred, dao, scope).flush()

        assertEquals("Force closed", server.notes[SHED])
    }

    @Test
    fun `text is sent exactly as typed`() = runBlocking {
        val text = "  - [ ] **not a tick box**\n\n\t[[not a link]]  \n"

        sync.save(SHED, text).join()

        assertEquals(text, server.notes[SHED])
    }

    // ---- Saved copies ----

    @Test
    fun `a delivered save can be reopened with Alfred gone`() = runBlocking {
        sync.save(SHED, "Written here\nnever fetched").join()
        server.down = true

        val result = alfred.getNote(SHED)

        assertEquals("Written here\nnever fetched", (result as AlfredResult.Stale).data.text)
    }

    @Test
    fun `a delivered save joins the saved list without making it look newer`() = runBlocking {
        cache.put(AlfredRepository.ENDPOINT_NOTES, """[{"id":"$ATTIC","title":"Attic"}]""", LONG_AGO)

        sync.save(SHED, "Shed\nshelves").join()

        assertEquals(listOf(SHED, ATTIC), alfred.savedNotes()!!.map { it.id })
        assertEquals("Shed", alfred.savedNotes()!!.first().title)
        assertEquals(LONG_AGO, cache.get(AlfredRepository.ENDPOINT_NOTES)!!.cachedAtMillis)
    }

    @Test
    fun `a delivered save replaces its own row in the saved list`() = runBlocking {
        cache.put(AlfredRepository.ENDPOINT_NOTES, """[{"id":"$SHED","title":"Old title"}]""", LONG_AGO)

        sync.save(SHED, "New title").join()

        assertEquals(listOf("New title"), alfred.savedNotes()!!.map { it.title })
    }

    @Test
    fun `a delivered delete removes both saved copies`() = runBlocking {
        cache.put(AlfredRepository.ENDPOINT_NOTES, """[{"id":"$SHED"},{"id":"$ATTIC"}]""", LONG_AGO)
        cache.put(AlfredRepository.noteEndpoint(SHED), """{"id":"$SHED","text":"Gone"}""", LONG_AGO)

        sync.delete(SHED).join()

        assertEquals(listOf(ATTIC), alfred.savedNotes()!!.map { it.id })
        assertNull(cache.get(AlfredRepository.noteEndpoint(SHED)))
        assertEquals(LONG_AGO, cache.get(AlfredRepository.ENDPOINT_NOTES)!!.cachedAtMillis)
    }

    @Test
    fun `a failed write leaves the saved copies alone`() = runBlocking {
        cache.put(AlfredRepository.ENDPOINT_NOTES, """[{"id":"$SHED"}]""", LONG_AGO)
        server.down = true

        sync.delete(SHED).join()

        assertEquals(listOf(SHED), alfred.savedNotes()!!.map { it.id })
    }

    private companion object {
        const val SHED = "11111111-2222-4333-8444-555555555555"
        const val ATTIC = "66666666-7777-4888-8999-aaaaaaaaaaaa"
        const val LONG_AGO = 1_000L
    }
}
