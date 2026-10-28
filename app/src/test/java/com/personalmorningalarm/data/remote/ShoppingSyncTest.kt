package com.personalmorningalarm.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.dao.PendingShoppingWriteDao
import com.personalmorningalarm.data.entity.PendingShoppingWrite
import com.personalmorningalarm.data.entity.ShoppingVerb
import com.personalmorningalarm.data.model.CalendarEventsDto
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.InboxCaptureDto
import com.personalmorningalarm.data.model.ScheduleTaskDto
import com.personalmorningalarm.data.model.ShoppingItemDto
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.model.WeekScheduleDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.RequestBody
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
 * The shopping queue's replay contract — mirrors [ChalkboardSyncTest] — plus the
 * one thing that's new here: entries carry a [PendingShoppingWrite.listId] and
 * replay must dispatch each to its own list's endpoint.
 */
@RunWith(RobolectricTestRunner::class)
class ShoppingSyncTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.pendingShoppingWriteDao()

    /** Every write the fake Alfred saw, in order — "list:ADD:text" / "list:TICK:line" / "list:DROP:line". */
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
                override suspend fun getCalendarEvents(start: String, end: String): CalendarEventsDto =
                    throw IOException()
                override suspend fun getChalkboard(): List<ChalkboardTaskDto> = throw IOException()
                override suspend fun addChalkboardItem(body: ChalkboardAddRequest): Response<Unit> = throw IOException()
                override suspend fun tickChalkboardItem(body: ChalkboardLineRequest): Response<Unit> = throw IOException()
                override suspend fun dropChalkboardItem(body: ChalkboardLineRequest): Response<Unit> = throw IOException()

                override suspend fun getShoppingLists(): List<ShoppingListSummaryDto> = throw IOException()
                override suspend fun getShoppingList(listId: String): com.personalmorningalarm.data.model.ShoppingListDetailDto =
                    throw IOException()
                override suspend fun createShoppingList(body: ShoppingCreateRequest): Response<ShoppingListSummaryDto> =
                    throw IOException()

                override suspend fun addShoppingItem(listId: String, body: ShoppingAddRequest): Response<Unit> =
                    write("$listId:ADD:${body.text}")

                override suspend fun tickShoppingItem(listId: String, body: ShoppingLineRequest): Response<Unit> =
                    write("$listId:TICK:${body.line}")

                override suspend fun dropShoppingItem(listId: String, body: ShoppingLineRequest): Response<Unit> =
                    write("$listId:DROP:${body.line}")

                override suspend fun getInbox(): List<InboxCaptureDto> = throw IOException()

                override suspend fun capture(body: RequestBody): Response<Unit> = throw IOException()

                private fun write(label: String): Response<Unit> {
                    writesSeen += label
                    val next = responses.removeFirstOrNull() ?: throw IOException("Alfred unreachable")
                    return next()
                }
            }
        }
    )

    private val sync = ShoppingSync(repository, dao)

    private val garden = "6-life/shopping/garden.md"
    private val fashion = "6-life/shopping/fashion.md"

    private fun accept() = { Response.success(Unit) }

    private fun staleTarget(): () -> Response<Unit> = {
        val body = """{"detail":{"error":"item not found","list_id":"$garden","items":${Gson().toJson(emptyList<ShoppingItemDto>())}}}"""
        Response.error(404, ResponseBody.create(MediaType.parse("application/json"), body))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `flush replays the queue in capture order across lists and empties it`() = runBlocking {
        sync.enqueue(garden, ShoppingVerb.ADD, "Hedge shears")
        sync.enqueue(fashion, ShoppingVerb.TICK, "Scarf", "- [ ] Scarf")
        sync.enqueue(garden, ShoppingVerb.DROP, "Old idea", "- [ ] Old idea")
        repeat(3) { responses += accept() }

        val outcome = sync.flush()

        assertEquals(
            listOf("$garden:ADD:Hedge shears", "$fashion:TICK:- [ ] Scarf", "$garden:DROP:- [ ] Old idea"),
            writesSeen
        )
        assertEquals(3, outcome.delivered)
        assertEquals(0, outcome.remaining)
        assertFalse(sync.hasPending())
    }

    @Test
    fun `an unreachable Alfred stops the flush and keeps everything queued`() = runBlocking {
        sync.enqueue(garden, ShoppingVerb.ADD, "Hedge shears")
        sync.enqueue(fashion, ShoppingVerb.ADD, "Scarf")

        val outcome = sync.flush()

        assertEquals(0, outcome.delivered)
        assertEquals(2, outcome.remaining)
        assertEquals(1, writesSeen.size)
        assertTrue(sync.hasPending())
    }

    @Test
    fun `a stale target is failed and kept, and never blocks the rest of the queue`() = runBlocking {
        sync.enqueue(garden, ShoppingVerb.TICK, "Long gone", "- [ ] Long gone")
        sync.enqueue(fashion, ShoppingVerb.ADD, "Scarf")
        responses += staleTarget()
        responses += accept()

        val outcome = sync.flush()

        assertEquals(1, outcome.delivered)
        assertEquals(1, outcome.conflicted)
        assertEquals(0, outcome.remaining)
        assertFalse(sync.hasPending())
        val kept = dao.observeAllOnce()
        assertEquals(1, kept.size)
        assertTrue(kept.single().failed)
        assertEquals(garden, kept.single().listId)
    }

    @Test
    fun `dismissing failed entries clears only that list's notice`() = runBlocking {
        sync.enqueue(garden, ShoppingVerb.TICK, "Long gone", "- [ ] Long gone")
        sync.enqueue(fashion, ShoppingVerb.TICK, "Also gone", "- [ ] Also gone")
        responses += staleTarget()
        responses += staleTarget()
        sync.flush()
        assertEquals(2, dao.observeAllOnce().size)

        sync.dismissFailed(garden)

        val remaining = dao.observeAllOnce()
        assertEquals(1, remaining.size)
        assertEquals(fashion, remaining.single().listId)
    }

    @Test
    fun `an entry with an unknown verb is failed, not fatal, and the rest continue`() = runBlocking {
        dao.insert(
            PendingShoppingWrite(
                listId = garden, verb = "TELEPORT", text = "From a newer build", line = null, createdAt = 0L
            )
        )
        sync.enqueue(garden, ShoppingVerb.ADD, "Hedge shears")
        responses += accept()

        val outcome = sync.flush()

        assertEquals(1, outcome.delivered)
        assertEquals(1, outcome.conflicted)
        assertEquals(listOf("$garden:ADD:Hedge shears"), writesSeen)
    }
}

/** One-shot read of the full table, for asserting on kept/failed rows. */
private suspend fun PendingShoppingWriteDao.observeAllOnce(): List<PendingShoppingWrite> =
    observeAll().first()
