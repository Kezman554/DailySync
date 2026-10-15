package com.personalmorningalarm.ui

import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.personalmorningalarm.data.entity.ChalkboardVerb
import com.personalmorningalarm.data.model.CalendarEventsDto
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.ScheduleTaskDto
import com.personalmorningalarm.data.model.WeekScheduleDto
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

/**
 * The Today screen's contract: both feeds fetched on open and on refresh, and —
 * the point of the screen — one being unreachable never takes the other down.
 */
@RunWith(RobolectricTestRunner::class)
class TodayViewModelTest : ViewModelTestSupport() {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val schedule = listOf(ScheduleTaskDto("Gym", "am"))
    private val chalkboard = listOf(ChalkboardTaskDto("Plant the bamboo", "2026-07-05"))

    // An Alfred whose answers can change mid-test, so refresh() can be exercised
    // for real rather than by swapping in a second ViewModel. Null = unreachable.
    private var scheduleResponse: List<ScheduleTaskDto>? = null
    private var chalkboardResponse: List<ChalkboardTaskDto>? = null
    private var writeResponse: (() -> Response<Unit>)? = null
    private var writesSeen = 0

    private val alfred by lazy {
        AlfredRepository(
            AlfredSettings(context),
            AlfredResponseCache(context),
            serviceProvider = {
                object : AlfredApiService {
                    override suspend fun getDailySchedule(): List<ScheduleTaskDto> =
                        scheduleResponse ?: throw IOException("Alfred unreachable")

                    override suspend fun getChalkboard(): List<ChalkboardTaskDto> =
                        chalkboardResponse ?: throw IOException("Alfred unreachable")

                    // The Today screen never asks for the week — see the week-screen tests.
                    override suspend fun getWeekSchedule(): WeekScheduleDto =
                        throw IOException("Alfred unreachable")

                    override suspend fun getCalendarEvents(start: String, end: String): CalendarEventsDto =
                        throw IOException("Alfred unreachable")

                    override suspend fun addChalkboardItem(body: ChalkboardAddRequest): Response<Unit> = write()

                    override suspend fun tickChalkboardItem(body: ChalkboardLineRequest): Response<Unit> = write()

                    override suspend fun dropChalkboardItem(body: ChalkboardLineRequest): Response<Unit> = write()

                    override suspend fun getShoppingLists(): List<com.personalmorningalarm.data.model.ShoppingListSummaryDto> =
                        throw IOException("Alfred unreachable")

                    override suspend fun getShoppingList(listId: String): com.personalmorningalarm.data.model.ShoppingListDetailDto =
                        throw IOException("Alfred unreachable")

                    override suspend fun createShoppingList(
                        body: ShoppingCreateRequest
                    ): Response<com.personalmorningalarm.data.model.ShoppingListSummaryDto> =
                        throw IOException("Alfred unreachable")

                    override suspend fun addShoppingItem(listId: String, body: ShoppingAddRequest): Response<Unit> =
                        throw IOException("Alfred unreachable")

                    override suspend fun tickShoppingItem(listId: String, body: ShoppingLineRequest): Response<Unit> =
                        throw IOException("Alfred unreachable")

                    override suspend fun dropShoppingItem(listId: String, body: ShoppingLineRequest): Response<Unit> =
                        throw IOException("Alfred unreachable")

                    override suspend fun getInbox(): List<com.personalmorningalarm.data.model.InboxCaptureDto> =
                        throw IOException("Alfred unreachable")

                    override suspend fun capture(body: okhttp3.RequestBody): Response<Unit> =
                        throw IOException("Alfred unreachable")

                    private fun write(): Response<Unit> {
                        writesSeen++
                        return writeResponse?.invoke() ?: throw IOException("Alfred unreachable")
                    }
                }
            }
        )
    }

    // Events are hot and drop without a subscriber, so tests that expect one
    // subscribe here before poking the ViewModel.
    private val events = mutableListOf<TodayViewModel.TodayEvent>()
    private val eventScope = CoroutineScope(Dispatchers.Main + Job())

    private fun trackEvents(vm: TodayViewModel) {
        eventScope.launch { vm.events.collect { events += it } }
    }

    private fun awaitEvent(expected: TodayViewModel.TodayEvent) {
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

    /** A ViewModel that has finished its initial load. */
    private fun loadedViewModel(): TodayViewModel {
        val vm = track(TodayViewModel(alfred, sync))
        keepSubscribed(vm.state)
        awaitValue(vm.state) { !it.loading && it.schedule != null }
        return vm
    }

    @Test
    fun `both feeds load and loading clears`() {
        scheduleResponse = schedule
        chalkboardResponse = chalkboard

        val state = loadedViewModel().state.value

        assertEquals(schedule, (state.schedule as AlfredResult.Fresh).data)
        assertEquals(chalkboard, (state.chalkboard as AlfredResult.Fresh).data)
        assertEquals(false, state.loading)
    }

    @Test
    fun `a dead chalkboard leaves the schedule intact`() {
        scheduleResponse = schedule
        chalkboardResponse = null

        val state = loadedViewModel().state.value

        assertEquals(schedule, (state.schedule as AlfredResult.Fresh).data)
        assertEquals(AlfredResult.Unavailable, state.chalkboard)
    }

    @Test
    fun `a dead schedule leaves the chalkboard intact`() {
        scheduleResponse = null
        chalkboardResponse = chalkboard

        val state = loadedViewModel().state.value

        assertEquals(AlfredResult.Unavailable, state.schedule)
        assertEquals(chalkboard, (state.chalkboard as AlfredResult.Fresh).data)
    }

    @Test
    fun `with Alfred fully down and nothing cached, both sections are unavailable`() {
        scheduleResponse = null
        chalkboardResponse = null

        val state = loadedViewModel().state.value

        assertEquals(AlfredResult.Unavailable, state.schedule)
        assertEquals(AlfredResult.Unavailable, state.chalkboard)
    }

    @Test
    fun `refresh picks up Alfred coming back`() {
        scheduleResponse = null
        chalkboardResponse = null
        val vm = loadedViewModel()
        assertEquals(AlfredResult.Unavailable, vm.state.value.schedule)

        // Alfred returns, and the user pulls to refresh.
        scheduleResponse = schedule
        chalkboardResponse = chalkboard
        vm.refresh()

        val state = awaitValue(vm.state) { it.schedule is AlfredResult.Fresh }
        assertEquals(schedule, (state.schedule as AlfredResult.Fresh).data)
        assertEquals(chalkboard, (state.chalkboard as AlfredResult.Fresh).data)
    }

    @Test
    fun `refresh after Alfred goes away serves each feed's cached copy, marked stale`() {
        scheduleResponse = schedule
        chalkboardResponse = chalkboard
        val vm = loadedViewModel() // fills the cache

        scheduleResponse = null
        chalkboardResponse = null
        vm.refresh()

        val state = awaitValue(vm.state) { it.schedule is AlfredResult.Stale }
        assertTrue(state.chalkboard is AlfredResult.Stale)
        assertEquals(schedule, (state.schedule as AlfredResult.Stale).data)
        assertEquals(chalkboard, (state.chalkboard as AlfredResult.Stale).data)
    }

    // --- chalkboard writes ---

    private val fenceLine = "- [ ] Fix fence (2026-07-18)"
    private val fence = ChalkboardTaskDto("Fix fence", "2026-07-18", fenceLine)

    /** A ViewModel with a live chalkboard on screen — the only state writes run from. */
    private fun writableViewModel(items: List<ChalkboardTaskDto>): TodayViewModel {
        scheduleResponse = schedule
        chalkboardResponse = items
        val vm = loadedViewModel()
        trackEvents(vm)
        return vm
    }

    @Test
    fun `an accepted add reconciles to Alfred's copy of the list`() {
        val vm = writableViewModel(chalkboard)

        // Alfred takes the write; its list then carries the item with its real line.
        writeResponse = { Response.success(Unit) }
        chalkboardResponse = chalkboard + fence
        vm.addItem("Fix fence")

        val state = awaitValue(vm.state) {
            (it.chalkboard as? AlfredResult.Fresh)?.data == chalkboard + fence
        }
        assertTrue(state.chalkboard is AlfredResult.Fresh)
    }

    @Test
    fun `blank input never reaches Alfred`() {
        val vm = writableViewModel(chalkboard)

        vm.addItem("   \n ")

        // Nothing to wait for — give any wrongly-launched write time to land.
        Thread.sleep(150)
        assertEquals(0, writesSeen)
        assertEquals(chalkboard, (vm.state.value.chalkboard as AlfredResult.Fresh).data)
    }

    @Test
    fun `a tick keeps the item listed as flipped, despite Alfred hiding ticked items`() {
        val vm = writableViewModel(listOf(fence))
        writeResponse = { Response.success(Unit) }
        // Alfred's listings exclude ticked items immediately — a refetch here
        // would hide the row. The ViewModel must keep the flipped local copy.
        chalkboardResponse = emptyList()

        vm.tickItem(fenceLine)

        val state = awaitValue(vm.state) {
            (it.chalkboard as? AlfredResult.Fresh)?.data?.single()?.line?.contains("[x]") == true
        }
        assertEquals(1, (state.chalkboard as AlfredResult.Fresh).data.size)
    }

    @Test
    fun `a drop removes the item and reconciles`() {
        val vm = writableViewModel(chalkboard + fence)
        writeResponse = { Response.success(Unit) }
        chalkboardResponse = chalkboard

        vm.dropItem(fenceLine)

        awaitValue(vm.state) { (it.chalkboard as? AlfredResult.Fresh)?.data == chalkboard }
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

        vm.tickItem(fenceLine)

        awaitValue(vm.state) { (it.chalkboard as? AlfredResult.Fresh)?.data == current }
        awaitEvent(TodayViewModel.TodayEvent.LIST_REFRESHED)
    }

    @Test
    fun `a write Alfred never received is queued, not lost, over the cached list`() {
        val vm = writableViewModel(listOf(fence))

        // Alfred vanishes between the fetch and the tap.
        writeResponse = null
        chalkboardResponse = null
        vm.tickItem(fenceLine)

        // The optimistic flip is undone by the reconciling refetch (the cached
        // pre-write list) — and the tick sits in the queue, where the merged
        // rendering re-applies it.
        val state = awaitValue(vm.state) {
            it.chalkboard is AlfredResult.Stale && it.pending.size == 1
        }
        assertEquals(listOf(fence), (state.chalkboard as AlfredResult.Stale).data)
        assertEquals(ChalkboardVerb.TICK.name, state.pending.single().verb)
        assertEquals(fenceLine, state.pending.single().line)
        awaitEvent(TodayViewModel.TodayEvent.SAVED_OFFLINE)
    }

    // --- the offline queue ---

    /** A ViewModel whose chalkboard is a cached (stale) copy — the offline state. */
    private fun offlineViewModel(items: List<ChalkboardTaskDto>): TodayViewModel {
        scheduleResponse = schedule
        chalkboardResponse = items
        val vm = loadedViewModel() // fills the cache
        scheduleResponse = null
        chalkboardResponse = null
        vm.refresh()
        awaitValue(vm.state) { it.chalkboard is AlfredResult.Stale }
        trackEvents(vm)
        writesSeen = 0
        return vm
    }

    @Test
    fun `offline writes go to the queue without touching the network`() {
        val vm = offlineViewModel(listOf(fence))

        vm.addItem("Buy stamps")
        vm.tickItem(fenceLine)

        val state = awaitValue(vm.state) { it.pending.size == 2 }
        assertEquals(0, writesSeen)
        assertEquals(
            listOf(ChalkboardVerb.ADD.name, ChalkboardVerb.TICK.name),
            state.pending.map { it.verb }
        )
        // The tick queues the item's text, so a conflict notice can name it.
        assertEquals("Fix fence", state.pending[1].text)
        awaitEvent(TodayViewModel.TodayEvent.SAVED_OFFLINE)
    }

    @Test
    fun `online writes never touch the queue`() {
        val vm = writableViewModel(chalkboard)
        writeResponse = { Response.success(Unit) }
        chalkboardResponse = chalkboard + fence

        vm.addItem("Fix fence")

        awaitValue(vm.state) { (it.chalkboard as? AlfredResult.Fresh)?.data == chalkboard + fence }
        assertEquals(1, writesSeen)
        assertTrue(vm.state.value.pending.isEmpty())
    }

    @Test
    fun `a flush triggered outside the screen still reconciles the open list`() {
        val vm = offlineViewModel(listOf(fence))
        vm.addItem("Buy stamps")
        awaitValue(vm.state) { it.pending.size == 1 }

        // Alfred comes back and something else — MainActivity's network-change
        // trigger — runs the flush, not this screen.
        val delivered = listOf(fence, ChalkboardTaskDto("Buy stamps", null, "- [ ] Buy stamps"))
        writeResponse = { Response.success(Unit) }
        scheduleResponse = schedule
        chalkboardResponse = delivered
        runBlocking { sync.flush() }

        // The delivered item must reappear as a real fetched row, not vanish
        // with its queue entry.
        val state = awaitValue(vm.state) {
            it.pending.isEmpty() && (it.chalkboard as? AlfredResult.Fresh)?.data == delivered
        }
        assertTrue(state.chalkboard is AlfredResult.Fresh)
    }

    @Test
    fun `refresh flushes the queue before refetching, so the list lands reconciled`() {
        val vm = offlineViewModel(listOf(fence))
        vm.addItem("Buy stamps")
        awaitValue(vm.state) { it.pending.size == 1 }

        // Home WiFi: Alfred is back, holding the delivered item.
        val delivered = listOf(fence, ChalkboardTaskDto("Buy stamps", null, "- [ ] Buy stamps"))
        writeResponse = { Response.success(Unit) }
        scheduleResponse = schedule
        chalkboardResponse = delivered
        vm.refresh()

        val state = awaitValue(vm.state) {
            it.pending.isEmpty() && (it.chalkboard as? AlfredResult.Fresh)?.data == delivered
        }
        assertEquals(1, writesSeen)
        assertTrue(state.chalkboard is AlfredResult.Fresh)
    }
}
