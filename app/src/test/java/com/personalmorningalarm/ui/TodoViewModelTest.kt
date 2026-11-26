package com.personalmorningalarm.ui

import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.personalmorningalarm.data.entity.ChalkboardVerb
import com.personalmorningalarm.data.model.CalendarEventsDto
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.ScheduleTaskDto
import com.personalmorningalarm.data.model.TodoFilter
import com.personalmorningalarm.data.model.TodoRow
import com.personalmorningalarm.data.model.TodoSource
import com.personalmorningalarm.data.model.WeekScheduleDto
import com.personalmorningalarm.data.phone.ListHome
import com.personalmorningalarm.data.remote.AlfredApiService
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResponseCache
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.AlfredSettings
import com.personalmorningalarm.data.remote.ChalkboardAddRequest
import com.personalmorningalarm.data.remote.ChalkboardLineRequest
import com.personalmorningalarm.data.remote.ChalkboardSync
import com.personalmorningalarm.data.remote.ShoppingAddRequest
import com.personalmorningalarm.data.remote.ShoppingCreateRequest
import com.personalmorningalarm.data.remote.ShoppingLineRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The To-do screen's contract: two lists, each fetched and written by name; one
 * being unreachable never takes the other down; hub writes go live or queue, and
 * a phone Mine never touches the network or the queue. The per-list write
 * behaviour is [HubTodoSource]'s, exercised through the ViewModel on Mine (Nick's
 * rolling to-do) as the old Today screen's was.
 */
@RunWith(RobolectricTestRunner::class)
class TodoViewModelTest : ViewModelTestSupport() {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val chalkboard = listOf(ChalkboardTaskDto("Plant the bamboo", "2026-07-05", "- [ ] Plant the bamboo (2026-07-05)"))
    private val familyBoard = listOf(ChalkboardTaskDto("Bins out", "2026-07-01", "- [ ] Bins out (2026-07-01)"))

    // An Alfred whose answers can change mid-test. Null = unreachable.
    private var nickResponse: List<ChalkboardTaskDto>? = null
    private var familyResponse: List<ChalkboardTaskDto>? = null
    private var writeResponse: (() -> Response<Unit>)? = null

    /** Every write, "list VERB:payload". */
    private val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Every read's query, "list/includeTicked". */
    private val reads: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val alfred by lazy {
        AlfredRepository(
            AlfredSettings(context),
            AlfredResponseCache(context),
            serviceProvider = {
                object : AlfredApiService {
                    override suspend fun getDailySchedule(): List<ScheduleTaskDto> = throw IOException()

                    override suspend fun getChalkboard(list: String, includeTicked: Boolean): List<ChalkboardTaskDto> {
                        reads += "$list/$includeTicked"
                        val answer = when (list) {
                            "nick" -> nickResponse
                            "family" -> familyResponse
                            else -> null
                        }
                        return answer ?: throw IOException("Alfred unreachable")
                    }

                    override suspend fun getWeekSchedule(): WeekScheduleDto = throw IOException()

                    override suspend fun createCalendarEvent(
                        body: com.personalmorningalarm.data.model.CalendarCreateRequest,
                        sent: com.personalmorningalarm.data.remote.SendMarker
                    ): Response<CalendarEventsDto> = throw IOException()
                    override suspend fun getCalendarEvents(start: String, end: String): CalendarEventsDto =
                        throw IOException()

                    override suspend fun addChalkboardItem(list: String, body: ChalkboardAddRequest): Response<Unit> =
                        write("$list ADD:${body.text}")

                    override suspend fun tickChalkboardItem(list: String, body: ChalkboardLineRequest): Response<Unit> =
                        write("$list TICK:${body.line}")

                    override suspend fun dropChalkboardItem(list: String, body: ChalkboardLineRequest): Response<Unit> =
                        write("$list DROP:${body.line}")

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

                    override suspend fun getNotes(): List<com.personalmorningalarm.data.model.NoteSummaryDto> = throw IOException()
                    override suspend fun getNote(noteId: String): com.personalmorningalarm.data.model.NoteDto = throw IOException()
                    override suspend fun saveNote(noteId: String, body: com.personalmorningalarm.data.remote.NoteSaveRequest): Response<Unit> =
                        throw IOException()
                    override suspend fun deleteNote(noteId: String): Response<Unit> = throw IOException()

                    override suspend fun setShoppingShared(listId: String, body: com.personalmorningalarm.data.remote.ShoppingShareRequest): Response<Unit> =
                        throw java.io.IOException()
                    override suspend fun uploadShoppingList(body: com.personalmorningalarm.data.remote.ShoppingUploadRequest): Response<com.personalmorningalarm.data.remote.ShoppingUploadResponse> =
                        throw java.io.IOException()
                    override suspend fun pullBackShoppingList(listId: String): Response<com.personalmorningalarm.data.remote.ShoppingPullBackResponse> =
                        throw java.io.IOException()

                    override suspend fun getInbox(): List<com.personalmorningalarm.data.model.InboxCaptureDto> =
                        throw IOException()

                    override suspend fun capture(body: okhttp3.RequestBody): Response<Unit> = throw IOException()

                    private fun write(label: String): Response<Unit> {
                        writes += label
                        return writeResponse?.invoke() ?: throw IOException("Alfred unreachable")
                    }
                }
            }
        )
    }

    // Events are hot and drop without a subscriber, so tests that expect one
    // subscribe here before poking the ViewModel.
    private val events = Collections.synchronizedList(mutableListOf<TodoEvent>())
    private val eventScope = CoroutineScope(Dispatchers.Main + Job())

    private fun trackEvents(vm: TodoViewModel) {
        eventScope.launch { vm.events.collect { events += it } }
    }

    private fun awaitEvent(expected: TodoEvent) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (expected in events) return
            Thread.sleep(15)
        }
        throw AssertionError("Event $expected never arrived; saw $events")
    }

    // The offline queue on the harness's in-memory Room, so persistence is exercised too.
    private val sync by lazy { ChalkboardSync(alfred, db.pendingChalkboardWriteDao()) }

    @After
    fun tearDownEvents() {
        eventScope.cancel()
    }

    private fun viewModel(mineHome: ListHome = ListHome.HUB): TodoViewModel =
        track(TodoViewModel(alfred, sync, phoneStore, mineHome))

    /** A ViewModel, Mine on the hub, that has finished its first read of both lists. */
    private fun loadedViewModel(): TodoViewModel {
        val vm = viewModel()
        awaitValue(vm.state) { settled(it.family) && settled(it.mine) }
        return vm
    }

    private fun settled(source: SourceState) = !source.loading && source.chalkboard != null

    private fun mineFresh(state: TodoViewModel.TodoState) = (state.mine.chalkboard as? AlfredResult.Fresh)?.data

    /** The first row on [source] whose vault line is [line]: what a tap on it acts on. */
    private fun row(vm: TodoViewModel, line: String, source: TodoSource = TodoSource.MINE): TodoRow =
        vm.state.value.rows.first { it.source == source && it.item.line == line }

    // --- reading both lists ---

    @Test
    fun `both lists are read by name, ticked items included`() {
        nickResponse = chalkboard
        familyResponse = familyBoard

        val state = loadedViewModel().state.value

        assertEquals(chalkboard, mineFresh(state))
        assertEquals(familyBoard, (state.family.chalkboard as AlfredResult.Fresh).data)
        assertEquals(listOf("family/true", "nick/true"), reads.sorted())
    }

    @Test
    fun `a dead family list leaves Mine intact`() {
        nickResponse = chalkboard
        familyResponse = null

        val state = loadedViewModel().state.value

        assertEquals(chalkboard, mineFresh(state))
        assertEquals(AlfredResult.Unavailable, state.family.chalkboard)
        assertEquals(null, state.family.items)
        assertEquals(listOf(TodoSource.MINE), state.rows.map { it.source })
    }

    @Test
    fun `a dead Mine leaves the family list intact`() {
        nickResponse = null
        familyResponse = familyBoard

        val state = loadedViewModel().state.value

        assertEquals(AlfredResult.Unavailable, state.mine.chalkboard)
        assertEquals(familyBoard, (state.family.chalkboard as AlfredResult.Fresh).data)
        assertEquals(listOf(TodoSource.FAMILY), state.rows.map { it.source })
    }

    @Test
    fun `a ticked item comes back greyed, not hidden`() {
        nickResponse = listOf(
            ChalkboardTaskDto("Plant the bamboo", "2026-07-05", "- [x] Plant the bamboo (2026-07-05)", ticked = true),
            ChalkboardTaskDto("Fix fence", "2026-07-18", "- [ ] Fix fence (2026-07-18)", ticked = false)
        )
        familyResponse = emptyList()

        val rows = loadedViewModel().state.value.rows

        assertEquals(listOf(true, false), rows.map { it.item.done })
    }

    @Test
    fun `refresh picks up Alfred coming back`() {
        val vm = loadedViewModel()
        assertEquals(AlfredResult.Unavailable, vm.state.value.mine.chalkboard)

        nickResponse = chalkboard
        familyResponse = familyBoard
        vm.refresh()

        val state = awaitValue(vm.state) { it.mine.chalkboard is AlfredResult.Fresh && it.family.chalkboard is AlfredResult.Fresh }
        assertEquals(chalkboard, mineFresh(state))
    }

    @Test
    fun `refresh after Alfred goes away serves each list's own saved copy, marked stale`() {
        nickResponse = chalkboard
        familyResponse = familyBoard
        val vm = loadedViewModel() // fills both caches

        nickResponse = null
        familyResponse = null
        vm.refresh()

        val state = awaitValue(vm.state) {
            it.mine.chalkboard is AlfredResult.Stale && it.family.chalkboard is AlfredResult.Stale
        }
        assertEquals(chalkboard, (state.mine.chalkboard as AlfredResult.Stale).data)
        assertEquals(familyBoard, (state.family.chalkboard as AlfredResult.Stale).data)
    }

    // --- the filter and the add picker ---

    @Test
    fun `it opens on All, adding to Mine, and the filter moves the picker`() {
        val vm = loadedViewModel()
        assertEquals(TodoFilter.ALL, vm.state.value.filter)
        assertEquals(TodoSource.MINE, vm.state.value.addTarget)

        vm.setFilter(TodoFilter.FAMILY)
        assertEquals(TodoSource.FAMILY, vm.state.value.addTarget)

        vm.setAddTarget(TodoSource.MINE)
        assertEquals(TodoSource.MINE, vm.state.value.addTarget)
        assertEquals(TodoFilter.FAMILY, vm.state.value.filter)

        vm.setFilter(TodoFilter.ALL)
        assertEquals(TodoSource.MINE, vm.state.value.addTarget)
    }

    @Test
    fun `an add from All goes to Mine, and one picked for Family goes to the family list`() {
        nickResponse = chalkboard
        familyResponse = familyBoard
        val vm = loadedViewModel()
        writeResponse = { Response.success(Unit) }

        vm.add("Oil the gate")
        vm.setAddTarget(TodoSource.FAMILY)
        vm.add("Book the boiler service")

        awaitValue(vm.state) { writes.size == 2 }
        assertEquals(listOf("nick ADD:Oil the gate", "family ADD:Book the boiler service"), writes.toList())
    }

    // --- hub writes (Mine on the hub) ---

    private val fenceLine = "- [ ] Fix fence (2026-07-18)"
    private val fence = ChalkboardTaskDto("Fix fence", "2026-07-18", fenceLine)

    /** A ViewModel with both lists live on screen — the state writes go straight out from. */
    private fun writableViewModel(items: List<ChalkboardTaskDto>): TodoViewModel {
        nickResponse = items
        familyResponse = familyBoard
        val vm = loadedViewModel()
        trackEvents(vm)
        return vm
    }

    @Test
    fun `an accepted add reconciles to Alfred's copy of the list`() {
        val vm = writableViewModel(chalkboard)

        writeResponse = { Response.success(Unit) }
        nickResponse = chalkboard + fence
        vm.add("Fix fence")

        awaitValue(vm.state) { mineFresh(it) == chalkboard + fence }
    }

    @Test
    fun `blank input never reaches Alfred`() {
        val vm = writableViewModel(chalkboard)

        vm.add("   \n ")

        // Nothing to wait for — give any wrongly-launched write time to land.
        Thread.sleep(150)
        assertTrue(writes.isEmpty())
        assertEquals(chalkboard, mineFresh(vm.state.value))
    }

    @Test
    fun `a tick keeps the item listed, greyed, without a refetch`() {
        val vm = writableViewModel(listOf(fence))
        writeResponse = { Response.success(Unit) }
        nickResponse = emptyList()

        vm.tick(row(vm, fenceLine))

        val state = awaitValue(vm.state) { mineFresh(it)?.single()?.line?.contains("[x]") == true }
        assertTrue(state.rows.single { it.source == TodoSource.MINE }.item.done)
    }

    @Test
    fun `a drop removes the item and reconciles`() {
        val vm = writableViewModel(chalkboard + fence)
        writeResponse = { Response.success(Unit) }
        nickResponse = chalkboard

        vm.drop(row(vm, fenceLine))

        awaitValue(vm.state) { mineFresh(it) == chalkboard }
        assertEquals(listOf("nick DROP:$fenceLine"), writes.toList())
    }

    @Test
    fun `a stale target swaps in the list Alfred returned and says so`() {
        val vm = writableViewModel(listOf(fence))
        val current = listOf(ChalkboardTaskDto("Plant the bamboo", null, "- [ ] Plant the bamboo"))
        writeResponse = {
            // The list rides inside FastAPI's detail wrapper, as the live API sends it.
            val body = """{"detail":{"error":"item not found","items":${Gson().toJson(current)}}}"""
            Response.error(404, ResponseBody.create(MediaType.parse("application/json"), body))
        }
        // The re-read that would bring back ticked rows finds Alfred gone again.
        nickResponse = null

        vm.tick(row(vm, fenceLine))

        awaitValue(vm.state) { mineFresh(it) == current }
        awaitEvent(TodoEvent.LIST_REFRESHED)
    }

    @Test
    fun `a write Alfred never received is queued, not lost, over the cached list`() {
        val vm = writableViewModel(listOf(fence))

        writeResponse = null
        nickResponse = null
        vm.tick(row(vm, fenceLine))

        val state = awaitValue(vm.state) {
            it.mine.chalkboard is AlfredResult.Stale && it.mine.queued.size == 1
        }
        assertEquals(listOf(fence), (state.mine.chalkboard as AlfredResult.Stale).data)
        assertEquals(ChalkboardVerb.TICK.name, state.mine.queued.single().verb)
        assertEquals(fenceLine, state.mine.queued.single().line)
        assertEquals("nick", state.mine.queued.single().list)
        awaitEvent(TodoEvent.SAVED_OFFLINE)
    }

    // --- the offline queue ---

    /** A ViewModel whose lists are saved (stale) copies — the offline state. */
    private fun offlineViewModel(items: List<ChalkboardTaskDto>): TodoViewModel {
        nickResponse = items
        familyResponse = familyBoard
        val vm = loadedViewModel() // fills the caches
        nickResponse = null
        familyResponse = null
        vm.refresh()
        awaitValue(vm.state) { it.mine.chalkboard is AlfredResult.Stale && it.family.chalkboard is AlfredResult.Stale }
        trackEvents(vm)
        writes.clear()
        return vm
    }

    @Test
    fun `offline writes go to the queue, each under its own list, without touching the network`() {
        val vm = offlineViewModel(listOf(fence))

        vm.add("Buy stamps")
        vm.tick(row(vm, fenceLine))
        vm.setAddTarget(TodoSource.FAMILY)
        vm.add("Book the boiler")

        val state = awaitValue(vm.state) { it.mine.queued.size == 2 && it.family.queued.size == 1 }
        assertTrue(writes.isEmpty())
        assertEquals(listOf(ChalkboardVerb.ADD.name, ChalkboardVerb.TICK.name), state.mine.queued.map { it.verb })
        // The tick queues the item's text, so a conflict notice can name it.
        assertEquals("Fix fence", state.mine.queued[1].text)
        assertEquals(listOf("family"), state.family.queued.map { it.list })
        // Each queued add shows on its own list only.
        assertEquals(TodoSource.FAMILY, state.rows.single { it.item.task == "Book the boiler" }.source)
        assertEquals(TodoSource.MINE, state.rows.single { it.item.task == "Buy stamps" }.source)
        awaitEvent(TodoEvent.SAVED_OFFLINE)
    }

    @Test
    fun `online writes never touch the queue`() {
        val vm = writableViewModel(chalkboard)
        writeResponse = { Response.success(Unit) }
        nickResponse = chalkboard + fence

        vm.add("Fix fence")

        awaitValue(vm.state) { mineFresh(it) == chalkboard + fence }
        assertEquals(1, writes.size)
        assertTrue(vm.state.value.mine.queued.isEmpty())
    }

    @Test
    fun `a flush triggered outside the screen still reconciles the open list`() {
        val vm = offlineViewModel(listOf(fence))
        vm.add("Buy stamps")
        awaitValue(vm.state) { it.mine.queued.size == 1 }

        val delivered = listOf(fence, ChalkboardTaskDto("Buy stamps", null, "- [ ] Buy stamps"))
        writeResponse = { Response.success(Unit) }
        nickResponse = delivered
        familyResponse = familyBoard
        runBlocking { sync.flush() }

        awaitValue(vm.state) { it.mine.queued.isEmpty() && mineFresh(it) == delivered }
    }

    @Test
    fun `refresh flushes the queue before refetching, each write to its own list`() {
        val vm = offlineViewModel(listOf(fence))
        vm.add("Buy stamps")
        vm.setAddTarget(TodoSource.FAMILY)
        vm.add("Bins out")
        awaitValue(vm.state) { it.mine.queued.size == 1 && it.family.queued.size == 1 }

        val delivered = listOf(fence, ChalkboardTaskDto("Buy stamps", null, "- [ ] Buy stamps"))
        writeResponse = { Response.success(Unit) }
        nickResponse = delivered
        familyResponse = familyBoard
        vm.refresh()

        awaitValue(vm.state) {
            it.mine.queued.isEmpty() && it.family.queued.isEmpty() && mineFresh(it) == delivered
        }
        assertEquals(listOf("nick ADD:Buy stamps", "family ADD:Bins out"), writes.toList())
    }

    // --- Mine on the phone ---

    @Test
    fun `a phone Mine never touches Alfred or the queue, from All or offline`() {
        familyResponse = null
        val vm = viewModel(ListHome.PHONE)
        awaitValue(vm.state) { settled(it.family) && it.mine.items != null }
        assertEquals(listOf("family/true"), reads.toList())

        vm.add("Water the ferns")
        val state = awaitValue(vm.state) { it.mine.items?.size == 1 }

        assertTrue(writes.isEmpty())
        assertEquals(0, runBlocking { db.pendingChalkboardWriteDao().observeAll().first() }.size)
        assertEquals("Water the ferns", state.rows.single().item.task)
        assertEquals(TodoSource.MINE, state.rows.single().source)
        assertTrue(!state.mine.onHub)
    }

    @Test
    fun `a phone row ticks and stays, greyed`() {
        familyResponse = emptyList()
        val vm = viewModel(ListHome.PHONE)
        vm.add("Water the ferns")
        awaitValue(vm.state) { it.mine.items?.size == 1 }

        vm.tick(vm.state.value.rows.single())

        val state = awaitValue(vm.state) { it.mine.items?.single()?.done == true }
        assertTrue(state.rows.single().item.done)
    }

    // --- identical rows: one tap, one row, the row Alfred will change ---

    private val gateLine = "- [ ] Oil the gate (2026-11-06)"
    private val gate = ChalkboardTaskDto("Oil the gate", "2026-11-06", gateLine)
    private val tickedGate = gate.copy(line = "- [x] Oil the gate (2026-11-06)", ticked = true)

    private fun rows(vm: TodoViewModel) = mineFresh(vm.state.value)!!

    /** Holds writes open, so the optimistic state can be read before any refetch. */
    private fun holdWrites(): CountDownLatch {
        val held = CountDownLatch(1)
        writeResponse = {
            held.await(3, TimeUnit.SECONDS)
            Response.success(Unit)
        }
        return held
    }

    @Test
    fun `a tick on two identical rows flips one`() {
        val vm = writableViewModel(listOf(gate, gate))
        val held = holdWrites()

        vm.tick(row(vm, gateLine))

        assertEquals(listOf(tickedGate, gate), rows(vm))
        held.countDown()
    }

    @Test
    fun `a second tick on the same line flips the second row`() {
        val vm = writableViewModel(listOf(gate, gate))
        val held = holdWrites()

        vm.tick(row(vm, gateLine))
        vm.tick(row(vm, gateLine))

        assertEquals(listOf(tickedGate, tickedGate), rows(vm))
        held.countDown()
    }

    @Test
    fun `a drop on two identical rows removes one`() {
        val vm = writableViewModel(listOf(gate, gate))
        val held = holdWrites()
        nickResponse = listOf(gate)

        vm.drop(row(vm, gateLine))

        assertEquals(listOf(gate), rows(vm))
        held.countDown()
    }
}
