package com.personalmorningalarm.data.remote

import com.personalmorningalarm.data.model.ChalkboardList
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.dao.PendingChalkboardWriteDao
import com.personalmorningalarm.data.entity.ChalkboardVerb
import com.personalmorningalarm.data.entity.PendingChalkboardWrite
import com.personalmorningalarm.data.model.CalendarEventsDto
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.ScheduleTaskDto
import com.personalmorningalarm.data.model.WeekScheduleDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response
import java.io.IOException

/**
 * The offline queue's replay contract: FIFO, delivered entries leave, a stale
 * target is failed (kept for the notice) without blocking the rest, and an
 * unreachable Alfred stops the flush with everything left still queued.
 */
@RunWith(RobolectricTestRunner::class)
class ChalkboardSyncTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.pendingChalkboardWriteDao()

    /** Every write the fake Alfred saw, in order — "ADD:text" / "TICK:line" / "DROP:line". */
    private val writesSeen = mutableListOf<String>()

    /** Per-write behaviour, consumed in order; missing entries mean "unreachable". */
    private val responses = ArrayDeque<() -> Response<Unit>>()

    private val repository = AlfredRepository(
        AlfredSettings(context),
        AlfredResponseCache(context),
        serviceProvider = {
            object : AlfredApiService {
                override suspend fun getDailySchedule(): List<ScheduleTaskDto> = throw IOException()
                override suspend fun getWeekSchedule(): WeekScheduleDto = throw IOException()
                override suspend fun createCalendarEvent(
                    body: com.personalmorningalarm.data.model.CalendarCreateRequest,
                    sent: com.personalmorningalarm.data.remote.SendMarker
                ): Response<CalendarEventsDto> = throw IOException()
                override suspend fun getCalendarEvents(start: String, end: String): CalendarEventsDto =
                    throw IOException()
                override suspend fun getChalkboard(list: String, includeTicked: Boolean): List<ChalkboardTaskDto> = throw IOException()

                override suspend fun addChalkboardItem(list: String, body: ChalkboardAddRequest): Response<Unit> =
                    write(list, "ADD:${body.text}")

                override suspend fun tickChalkboardItem(list: String, body: ChalkboardLineRequest): Response<Unit> =
                    write(list, "TICK:${body.line}")

                override suspend fun dropChalkboardItem(list: String, body: ChalkboardLineRequest): Response<Unit> =
                    write(list, "DROP:${body.line}")

                override suspend fun getShoppingLists(): List<com.personalmorningalarm.data.model.ShoppingListSummaryDto> =
                    throw IOException()

                override suspend fun getShoppingList(listId: String): com.personalmorningalarm.data.model.ShoppingListDetailDto =
                    throw IOException()

                override suspend fun deleteShoppingList(listId: String): Response<com.personalmorningalarm.data.remote.ShoppingDeleteResponse> =
                    throw IOException()

                override suspend fun createShoppingList(
                    body: ShoppingCreateRequest
                ): Response<com.personalmorningalarm.data.model.ShoppingListSummaryDto> = throw IOException()

                override suspend fun addShoppingItem(listId: String, body: ShoppingAddRequest): Response<Unit> =
                    throw IOException()

                override suspend fun tickShoppingItem(listId: String, body: ShoppingLineRequest): Response<Unit> =
                    throw IOException()

                override suspend fun dropShoppingItem(listId: String, body: ShoppingLineRequest): Response<Unit> =
                    throw IOException()

                override suspend fun getNotes(): List<com.personalmorningalarm.data.model.NoteSummaryDto> = throw java.io.IOException()
                override suspend fun getNote(noteId: String): com.personalmorningalarm.data.model.NoteDto = throw java.io.IOException()
                override suspend fun saveNote(noteId: String, body: com.personalmorningalarm.data.remote.NoteSaveRequest): Response<Unit> =
                    throw java.io.IOException()
                override suspend fun deleteNote(noteId: String): Response<Unit> = throw java.io.IOException()

                override suspend fun setShoppingShared(listId: String, body: com.personalmorningalarm.data.remote.ShoppingShareRequest): Response<Unit> =
                    throw java.io.IOException()
                override suspend fun uploadShoppingList(body: com.personalmorningalarm.data.remote.ShoppingUploadRequest): Response<com.personalmorningalarm.data.remote.ShoppingUploadResponse> =
                    throw java.io.IOException()
                override suspend fun pullBackShoppingList(listId: String): Response<com.personalmorningalarm.data.remote.ShoppingPullBackResponse> =
                    throw java.io.IOException()

                override suspend fun getInbox(): List<com.personalmorningalarm.data.model.InboxCaptureDto> =
                    throw IOException()

                override suspend fun capture(body: okhttp3.RequestBody): Response<Unit> = throw IOException()

                private fun write(list: String, label: String): Response<Unit> {
                    // Nick's writes read as before; any other list says which.
                    writesSeen += if (list == "nick") label else "$list $label"
                    val next = responses.removeFirstOrNull() ?: throw IOException("Alfred unreachable")
                    return next()
                }
            }
        }
    )

    private val sync = ChalkboardSync(repository, dao)

    private fun accept() = { Response.success(Unit) }

    private fun staleTarget(): () -> Response<Unit> = {
        val body = """{"detail":{"error":"item not found","items":${Gson().toJson(emptyList<ChalkboardTaskDto>())}}}"""
        Response.error(404, ResponseBody.create(MediaType.parse("application/json"), body))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `each queued write goes back to the list it was made on`() = runBlocking {
        sync.enqueue(ChalkboardList.FAMILY, ChalkboardVerb.ADD, "Bins out")
        sync.enqueue(ChalkboardList.NICK, ChalkboardVerb.ADD, "Buy stamps")
        sync.enqueue(ChalkboardList.FAMILY, ChalkboardVerb.TICK, "Book the boiler", "- [ ] Book the boiler")
        repeat(3) { responses += accept() }

        sync.flush()

        assertEquals(
            listOf("family ADD:Bins out", "ADD:Buy stamps", "family TICK:- [ ] Book the boiler"),
            writesSeen
        )
    }

    @Test
    fun `a write queued for a list this build doesn't know is failed, never guessed at`() = runBlocking {
        dao.insert(
            com.personalmorningalarm.data.entity.PendingChalkboardWrite(
                verb = ChalkboardVerb.ADD.name, text = "Somewhere new", line = null, createdAt = 1, list = "garage"
            )
        )
        responses += accept()

        val outcome = sync.flush()

        assertTrue(writesSeen.isEmpty())
        assertEquals(1, outcome.conflicted)
    }

    @Test
    fun `flush replays the queue in capture order and empties it`() = runBlocking {
        sync.enqueue(ChalkboardList.NICK, ChalkboardVerb.ADD, "Buy stamps")
        sync.enqueue(ChalkboardList.NICK, ChalkboardVerb.TICK, "Fix fence", "- [ ] Fix fence")
        sync.enqueue(ChalkboardList.NICK, ChalkboardVerb.DROP, "Old idea", "- [ ] Old idea")
        repeat(3) { responses += accept() }

        val outcome = sync.flush()

        assertEquals(
            listOf("ADD:Buy stamps", "TICK:- [ ] Fix fence", "DROP:- [ ] Old idea"),
            writesSeen
        )
        assertEquals(3, outcome.delivered)
        assertEquals(0, outcome.remaining)
        assertFalse(sync.hasPending())
    }

    @Test
    fun `an unreachable Alfred stops the flush and keeps everything queued`() = runBlocking {
        sync.enqueue(ChalkboardList.NICK, ChalkboardVerb.ADD, "Buy stamps")
        sync.enqueue(ChalkboardList.NICK, ChalkboardVerb.ADD, "Call the dentist")
        // No responses queued: the first write throws.

        val outcome = sync.flush()

        assertEquals(0, outcome.delivered)
        assertEquals(2, outcome.remaining)
        // Only the first entry was attempted — no pointless hammering after a miss.
        assertEquals(1, writesSeen.size)
        assertTrue(sync.hasPending())
    }

    @Test
    fun `a partial flush keeps only what Alfred didn't take`() = runBlocking {
        sync.enqueue(ChalkboardList.NICK, ChalkboardVerb.ADD, "Buy stamps")
        sync.enqueue(ChalkboardList.NICK, ChalkboardVerb.ADD, "Call the dentist")
        responses += accept() // first lands, then Alfred vanishes

        val outcome = sync.flush()

        assertEquals(1, outcome.delivered)
        assertEquals(1, outcome.remaining)
        // The survivor is the undelivered entry, still replayable next flush.
        responses += accept()
        val second = sync.flush()
        assertEquals(1, second.delivered)
        assertEquals(listOf("ADD:Buy stamps", "ADD:Call the dentist", "ADD:Call the dentist"), writesSeen)
        assertFalse(sync.hasPending())
    }

    @Test
    fun `a stale target is failed and kept, and never blocks the rest of the queue`() = runBlocking {
        sync.enqueue(ChalkboardList.NICK, ChalkboardVerb.TICK, "Long gone", "- [ ] Long gone")
        sync.enqueue(ChalkboardList.NICK, ChalkboardVerb.ADD, "Buy stamps")
        responses += staleTarget()
        responses += accept()

        val outcome = sync.flush()

        assertEquals(1, outcome.delivered)
        assertEquals(1, outcome.conflicted)
        assertEquals(0, outcome.remaining)
        // The failed entry is out of the replay set but kept for the notice…
        assertFalse(sync.hasPending())
        val kept = dao.observeAllOnce()
        assertEquals(1, kept.size)
        assertTrue(kept.single().failed)
        assertEquals("Long gone", kept.single().text)
        // …and a re-flush never retries it.
        writesSeen.clear()
        sync.flush()
        assertTrue(writesSeen.isEmpty())
    }

    @Test
    fun `dismissing failed entries clears the notice`() = runBlocking {
        sync.enqueue(ChalkboardList.NICK, ChalkboardVerb.TICK, "Long gone", "- [ ] Long gone")
        responses += staleTarget()
        sync.flush()
        assertEquals(1, dao.observeAllOnce().size)

        sync.dismissFailed()

        assertTrue(dao.observeAllOnce().isEmpty())
    }

    @Test
    fun `an entry with an unknown verb is failed, not fatal, and the rest continue`() = runBlocking {
        dao.insert(
            PendingChalkboardWrite(
                verb = "TELEPORT",
                text = "From a newer build",
                line = null,
                createdAt = 0L
            )
        )
        sync.enqueue(ChalkboardList.NICK, ChalkboardVerb.ADD, "Buy stamps")
        responses += accept()

        val outcome = sync.flush()

        assertEquals(1, outcome.delivered)
        assertEquals(1, outcome.conflicted)
        assertEquals(listOf("ADD:Buy stamps"), writesSeen)
    }
}

/** One-shot read of the full table, for asserting on kept/failed rows. */
private suspend fun PendingChalkboardWriteDao.observeAllOnce(): List<PendingChalkboardWrite> =
    observeAll().first()
