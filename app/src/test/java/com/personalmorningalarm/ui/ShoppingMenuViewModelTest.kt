package com.personalmorningalarm.ui

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.personalmorningalarm.data.entity.ShoppingVerb
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResponseCache
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.AlfredSettings
import com.personalmorningalarm.data.remote.ShoppingSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Deleting a list from the menu, against a stand-in for vault-api over real HTTP. */
@RunWith(RobolectricTestRunner::class)
class ShoppingMenuViewModelTest : ViewModelTestSupport() {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val settings = AlfredSettings(context)
    private val gson = Gson()
    private val server = MockWebServer()

    private val garden = ShoppingListSummaryDto(GARDEN, "Garden Tools — Seasonal Buy List", 5, 3)
    private val hardware = ShoppingListSummaryDto(HARDWARE, "Hardware", 2, 2)

    /** What the fake vault's discovery returns. */
    private val lists: MutableList<ShoppingListSummaryDto> =
        Collections.synchronizedList(mutableListOf(garden, hardware))

    private val deletes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Null: delete as the API does. Otherwise the answer every DELETE gets. */
    private var deleteAnswer: MockResponse? = null

    /** When set, deletes wait on it, so a test can act while one is with Alfred. */
    private var deleteGate: CountDownLatch? = null

    /** When set, list reads wait on it, so a refresh can be held open across a delete. */
    private var readGate: CountDownLatch? = null

    private val alfred by lazy { AlfredRepository(settings, AlfredResponseCache(context)) }
    private val dao by lazy { db.pendingShoppingWriteDao() }
    private val sync by lazy { ShoppingSync(alfred, dao) }

    private val events = Collections.synchronizedList(mutableListOf<ShoppingMenuViewModel.MenuEvent>())
    private val eventScope = CoroutineScope(Dispatchers.Main + Job())

    @Before
    fun setUp() {
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") {
                    val answer = gson.toJson(lists.toList())
                    readGate?.await(5, TimeUnit.SECONDS)
                    return MockResponse().setBody(answer)
                }
                deleteGate?.await(5, TimeUnit.SECONDS)
                deletes += request.path
                deleteAnswer?.let { return it }
                lists.removeAll { request.path.endsWith("/${it.id}") }
                return MockResponse().setBody(gson.toJson(mapOf("deleted" to GARDEN, "lists" to lists.toList())))
            }
        })
        server.start(InetAddress.getByName(LOCALHOST), 0)
        settings.setHost("$LOCALHOST:${server.port}")
    }

    @After
    fun tearDown() {
        deleteGate?.countDown()
        readGate?.countDown()
        eventScope.cancel()
        server.shutdown()
        AlfredReachability.reset()
    }

    private fun loadedViewModel(): ShoppingMenuViewModel {
        val vm = track(ShoppingMenuViewModel(alfred, sync))
        keepSubscribed(vm.state)
        eventScope.launch { vm.events.collect { events += it } }
        awaitValue(vm.state) { it.lists is AlfredResult.Fresh }
        return vm
    }

    private fun shown(vm: ShoppingMenuViewModel) = (vm.state.value.lists as AlfredResult.Fresh).data

    private fun savedLists(): AlfredResult<List<ShoppingListSummaryDto>> =
        runBlocking {
            server.shutdown()
            alfred.getShoppingLists()
        }

    private fun queueWrites() = runBlocking {
        sync.enqueue(GARDEN, ShoppingVerb.ADD, "Hedge shears")
        sync.enqueue(HARDWARE, ShoppingVerb.ADD, "Wood screws")
    }

    private fun queuedLists() = runBlocking { dao.getPending().map { it.listId } }

    private fun awaitEvent(expected: ShoppingMenuViewModel.MenuEvent) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (expected in events) return
            Thread.sleep(15)
        }
        throw AssertionError("Event $expected never arrived; saw $events")
    }

    @Test
    fun `a delete takes the list off the screen and out of the saved copy`() {
        val vm = loadedViewModel()

        vm.deleteList(garden)

        awaitValue(vm.state) { !it.deleting }
        assertEquals(listOf(hardware), shown(vm))
        assertEquals(1, deletes.size)
        // The tile's short name, not the vault's full title.
        awaitEvent(ShoppingMenuViewModel.MenuEvent.Deleted("Garden Tools"))
        assertEquals(listOf(hardware), (savedLists() as AlfredResult.Stale).data)
    }

    @Test
    fun `a delete removes the writes queued for that list and no other`() {
        val vm = loadedViewModel()
        queueWrites()

        vm.deleteList(garden)

        awaitValue(vm.state) { !it.deleting }
        assertEquals(listOf(HARDWARE), queuedLists())
    }

    @Test
    fun `a 404 with the current lists counts as gone`() {
        val vm = loadedViewModel()
        queueWrites()
        deleteAnswer = MockResponse().setResponseCode(404).setBody(
            gson.toJson(mapOf("detail" to mapOf("error" to "not found", "lists" to listOf(hardware))))
        )

        vm.deleteList(garden)

        awaitValue(vm.state) { !it.deleting }
        assertEquals(listOf(hardware), shown(vm))
        assertEquals(listOf(HARDWARE), queuedLists())
        awaitEvent(ShoppingMenuViewModel.MenuEvent.Deleted("Garden Tools"))
    }

    @Test
    fun `an unreachable Alfred says so and leaves the list and its queue in place`() {
        val vm = loadedViewModel()
        queueWrites()
        server.shutdown()

        vm.deleteList(garden)

        awaitValue(vm.state) { !it.deleting }
        assertEquals(listOf(garden, hardware), shown(vm))
        assertEquals(listOf(GARDEN, HARDWARE), queuedLists())
        awaitEvent(ShoppingMenuViewModel.MenuEvent.DeleteUnreachable)
    }

    @Test
    fun `a failed delete is never queued`() {
        val vm = loadedViewModel()
        server.shutdown()

        vm.deleteList(garden)

        awaitValue(vm.state) { !it.deleting }
        assertTrue(queuedLists().isEmpty())
    }

    @Test
    fun `an error from Alfred leaves the list in place too`() {
        val vm = loadedViewModel()
        queueWrites()
        deleteAnswer = MockResponse().setResponseCode(503).setBody("{}")

        vm.deleteList(garden)

        awaitValue(vm.state) { !it.deleting }
        assertEquals(listOf(garden, hardware), shown(vm))
        assertEquals(listOf(GARDEN, HARDWARE), queuedLists())
    }

    @Test
    fun `a second delete while one is with Alfred is ignored`() {
        val vm = loadedViewModel()
        val gate = CountDownLatch(1).also { deleteGate = it }

        vm.deleteList(garden)
        vm.deleteList(hardware)
        gate.countDown()

        awaitValue(vm.state) { !it.deleting }
        assertEquals(1, deletes.size)
    }

    @Test
    fun `leaving the screen mid-delete still clears the queue`() {
        val vm = loadedViewModel()
        queueWrites()
        val gate = CountDownLatch(1).also { deleteGate = it }

        vm.deleteList(garden)
        vm.viewModelScope.cancel()
        gate.countDown()

        val deadline = System.currentTimeMillis() + 3_000
        while (queuedLists().size > 1 && System.currentTimeMillis() < deadline) Thread.sleep(15)
        assertEquals(listOf(HARDWARE), queuedLists())
    }

    @Test
    fun `a refresh begun before the delete cannot bring the list back`() {
        val vm = loadedViewModel()
        val heldRead = CountDownLatch(1).also { readGate = it }
        vm.refresh()
        // The read has its answer, with the list still in it, and is held there.
        val deadline = System.currentTimeMillis() + 3_000
        while (server.requestCount < 2 && System.currentTimeMillis() < deadline) Thread.sleep(15)

        vm.deleteList(garden)
        awaitValue(vm.state) { !it.deleting }
        heldRead.countDown()

        Thread.sleep(300)
        assertEquals(listOf(hardware), shown(vm))
        assertEquals(false, vm.state.value.loading)
    }

    private companion object {
        const val LOCALHOST = "127.0.0.1"
        const val GARDEN = "garden-tools.md"
        const val HARDWARE = "hardware.md"
    }
}
