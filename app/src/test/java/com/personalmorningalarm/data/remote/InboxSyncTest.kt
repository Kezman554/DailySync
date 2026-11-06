package com.personalmorningalarm.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.dao.PendingInboxWriteDao
import com.personalmorningalarm.data.entity.PendingInboxWrite
import com.personalmorningalarm.data.model.CalendarEventsDto
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.InboxCaptureDto
import com.personalmorningalarm.data.model.ScheduleTaskDto
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.model.WeekScheduleDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okio.Buffer
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
 * The inbox queue's replay contract — mirrors [ShoppingSyncTest]. The difference
 * that matters here is the failure mode: a capture has no targeting key, so it can
 * never go stale; what it can do is be refused outright, and a refusal must not
 * stall the captures queued behind it.
 */
@RunWith(RobolectricTestRunner::class)
class InboxSyncTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.pendingInboxWriteDao()

    /** Every capture body the fake Alfred saw, in order. */
    private val capturesSeen = mutableListOf<String>()

    /** Per-write behaviour, consumed in order; missing entries mean "unreachable". */
    private val responses = ArrayDeque<() -> Response<Unit>>()

    private val repository = AlfredRepository(
        AlfredSettings(context),
        AlfredResponseCache(context),
        serviceProvider = {
            object : AlfredApiService {
                override suspend fun getDailySchedule(): List<ScheduleTaskDto> = throw IOException()
                override suspend fun getWeekSchedule(): WeekScheduleDto = throw IOException()
                override suspend fun getCalendarEvents(start: String, end: String): CalendarEventsDto =
                    throw IOException()
                override suspend fun getChalkboard(): List<ChalkboardTaskDto> = throw IOException()
                override suspend fun addChalkboardItem(body: ChalkboardAddRequest): Response<Unit> = throw IOException()
                override suspend fun tickChalkboardItem(body: ChalkboardLineRequest): Response<Unit> = throw IOException()
                override suspend fun dropChalkboardItem(body: ChalkboardLineRequest): Response<Unit> = throw IOException()

                override suspend fun getShoppingLists(): List<ShoppingListSummaryDto> = throw IOException()
                override suspend fun getShoppingList(listId: String): com.personalmorningalarm.data.model.ShoppingListDetailDto =
                    throw IOException()
                override suspend fun deleteShoppingList(listId: String): Response<com.personalmorningalarm.data.remote.ShoppingDeleteResponse> =
                    throw IOException()

                override suspend fun createShoppingList(body: ShoppingCreateRequest): Response<ShoppingListSummaryDto> =
                    throw IOException()
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

                override suspend fun getInbox(): List<InboxCaptureDto> = throw IOException()

                override suspend fun capture(body: RequestBody): Response<Unit> {
                    capturesSeen += body.readText()
                    val next = responses.removeFirstOrNull() ?: throw IOException("Alfred unreachable")
                    return next()
                }
            }
        }
    )

    private val sync = InboxSync(repository, dao)

    private fun accept() = { Response.success(Unit) }

    private fun refuse(): () -> Response<Unit> = {
        Response.error(422, ResponseBody.create(MediaType.parse("application/json"), """{"detail":"empty capture"}"""))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the capture body is sent as plain text, not as a JSON scalar`() = runBlocking {
        sync.enqueue("Buy a 12.5kg dumbbell pair")
        responses += accept()

        sync.flush()

        // Gson would have sent "\"Buy a 12.5kg dumbbell pair\"" — the endpoint takes text/plain.
        assertEquals(listOf("Buy a 12.5kg dumbbell pair"), capturesSeen)
        val contentType = AlfredRepository.capturePlainText("x").contentType()
        assertEquals("text", contentType?.type())
        assertEquals("plain", contentType?.subtype())
    }

    @Test
    fun `flush replays the queue in capture order and empties it`() = runBlocking {
        sync.enqueue("First thought")
        sync.enqueue("Second thought")
        sync.enqueue("Third thought")
        repeat(3) { responses += accept() }

        val outcome = sync.flush()

        assertEquals(listOf("First thought", "Second thought", "Third thought"), capturesSeen)
        assertEquals(3, outcome.delivered)
        assertEquals(0, outcome.remaining)
        assertFalse(sync.hasPending())
    }

    @Test
    fun `an unreachable Alfred stops the flush and keeps everything queued`() = runBlocking {
        sync.enqueue("First thought")
        sync.enqueue("Second thought")

        val outcome = sync.flush()

        assertEquals(0, outcome.delivered)
        assertEquals(2, outcome.remaining)
        assertEquals(1, capturesSeen.size)
        assertTrue(sync.hasPending())
    }

    @Test
    fun `a refused capture is failed and kept, and never blocks the rest of the queue`() = runBlocking {
        sync.enqueue("Something Alfred won't take")
        sync.enqueue("A fine thought")
        responses += refuse()
        responses += accept()

        val outcome = sync.flush()

        assertEquals(1, outcome.delivered)
        assertEquals(1, outcome.refused)
        assertEquals(0, outcome.remaining)
        assertFalse(sync.hasPending())
        val kept = dao.observeAllOnce()
        assertEquals(1, kept.size)
        assertTrue(kept.single().failed)
        assertEquals("Something Alfred won't take", kept.single().text)
    }

    @Test
    fun `dismissing clears the refused captures' notice`() = runBlocking {
        sync.enqueue("Refused")
        responses += refuse()
        sync.flush()
        assertEquals(1, dao.observeAllOnce().size)

        sync.dismissFailed()

        assertTrue(dao.observeAllOnce().isEmpty())
    }

    @Test
    fun `a multi-line capture survives the round trip intact`() = runBlocking {
        sync.enqueue("Line one\nLine two\n\nLine four")
        responses += accept()

        sync.flush()

        assertEquals(listOf("Line one\nLine two\n\nLine four"), capturesSeen)
    }
}

/** One-shot read of the full table, for asserting on kept/failed rows. */
private suspend fun PendingInboxWriteDao.observeAllOnce(): List<PendingInboxWrite> =
    observeAll().first()

/** Buffers a request body back into the text it carries. */
private fun RequestBody.readText(): String = Buffer().also { writeTo(it) }.readUtf8()
