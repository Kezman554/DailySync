package com.personalmorningalarm.ui

import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.personalmorningalarm.data.entity.ShoppingVerb
import com.personalmorningalarm.data.model.ShoppingItemDto
import com.personalmorningalarm.data.model.ShoppingListDetailDto
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResponseCache
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.AlfredSettings
import com.personalmorningalarm.data.remote.ShoppingLineRequest
import com.personalmorningalarm.data.remote.ShoppingSync
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One shopping list's writes against a stand-in for the Pi over real HTTP: it finds
 * a target as vault.py does (first exact line, and a tick rewrites the line), so what
 * the screen shows can be held against what the vault would really hold.
 */
@RunWith(RobolectricTestRunner::class)
class ShoppingListViewModelTest : ViewModelTestSupport() {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val settings = AlfredSettings(context)
    private val gson = Gson()
    private val server = MockWebServer()

    /** What the fake vault holds. */
    private val vault: MutableList<ShoppingItemDto> = Collections.synchronizedList(mutableListOf())

    /** Every write that reached the fake vault. */
    private val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private var answerWriteAfterSeconds = 0L

    /** When set, writes wait on it, so the optimistic state can be read before they land. */
    private var writeGate: CountDownLatch? = null

    private val alfred by lazy { AlfredRepository(settings, AlfredResponseCache(context)) }
    private val sync by lazy { ShoppingSync(alfred, db.pendingShoppingWriteDao()) }

    @Before
    fun setUp() {
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path
                if (request.method == "GET") {
                    return MockResponse().setBody(gson.toJson(ShoppingListDetailDto(null, vault.toList())))
                }
                writeGate?.await(5, TimeUnit.SECONDS)
                writes += path
                val body = request.body.readUtf8()
                when {
                    path.endsWith("/tick") -> tick(gson.fromJson(body, ShoppingLineRequest::class.java).line)
                    path.endsWith("/drop") -> drop(gson.fromJson(body, ShoppingLineRequest::class.java).line)
                    else -> gson.fromJson(body, Map::class.java)["text"].let { text ->
                        vault += ShoppingItemDto("$text", "- [ ] $text", false)
                    }
                }
                return MockResponse().setHeadersDelay(answerWriteAfterSeconds, TimeUnit.SECONDS).setBody("{}")
            }
        })
        server.start(InetAddress.getByName(LOCALHOST), 0)
        settings.setHost("$LOCALHOST:${server.port}")
    }

    private fun tick(line: String) = synchronized(vault) {
        val index = vault.indexOfFirst { it.line == line }
        vault[index] = vault[index].copy(line = line.replaceFirst("[ ]", "[x]"), ticked = true)
    }

    private fun drop(line: String) = synchronized(vault) {
        vault.removeAt(vault.indexOfFirst { it.line == line })
    }

    @After
    fun tearDown() {
        writeGate?.countDown()
        server.shutdown()
        AlfredReachability.reset()
    }

    private fun milk(ticked: Boolean = false) =
        ShoppingItemDto("Milk", if (ticked) TICKED_MILK else MILK, ticked)

    private fun loadedViewModel(): ShoppingListViewModel {
        val vm = track(ShoppingListViewModel(alfred, sync, LIST))
        keepSubscribed(vm.state)
        awaitValue(vm.state) { !it.loading && it.result is AlfredResult.Fresh }
        return vm
    }

    private fun rows(vm: ShoppingListViewModel): List<ShoppingItemDto> =
        (vm.state.value.result as AlfredResult.Fresh).data

    private fun awaitWrites(count: Int) {
        val deadline = System.currentTimeMillis() + 9_000
        while (writes.size < count && System.currentTimeMillis() < deadline) Thread.sleep(15)
        assertEquals(count, writes.size)
    }

    // --- slow writes ---

    @Test
    fun `a write answered after 5 seconds reaches Alfred once and queues nothing`() {
        val vm = loadedViewModel()
        answerWriteAfterSeconds = 5

        vm.addItem("Oat milk")

        // The reconcile finding Alfred's own line means the write came back Done.
        awaitValue(vm.state, timeoutMs = 9_000) { state ->
            (state.result as? AlfredResult.Fresh)?.data?.any { it.line != null } == true
        }
        val flush = runBlocking {
            assertTrue(db.pendingShoppingWriteDao().getPending().isEmpty())
            sync.flush()
        }
        assertEquals(0, flush.delivered)
        assertEquals(1, writes.size)
        assertEquals(1, vault.size)
    }

    @Test
    fun `a queued write answered after 5 seconds is delivered once and leaves the queue`() {
        val vm = loadedViewModel()
        answerWriteAfterSeconds = 5
        runBlocking {
            sync.enqueue(LIST, ShoppingVerb.ADD, "Oat milk")

            assertEquals(1, sync.flush().delivered)
            assertEquals(0, sync.flush().delivered)
        }

        assertEquals(1, writes.size)
        assertTrue(awaitValue(vm.state) { it.pending.isEmpty() }.pending.isEmpty())
    }

    @Test
    fun `a write that cannot connect still queues`() {
        val vm = loadedViewModel()
        server.shutdown()

        vm.addItem("Oat milk")

        awaitValue(vm.state) { it.pending.size == 1 }
        val queued = runBlocking { db.pendingShoppingWriteDao().getPending() }.single()
        assertEquals(ShoppingVerb.ADD.name, queued.verb)
        assertEquals("Oat milk", queued.text)
        assertTrue(writes.isEmpty())
    }

    // --- one tap, one row ---

    @Test
    fun `a tick on two identical rows crosses one and it stays crossed`() {
        vault += listOf(milk(), milk())
        val vm = loadedViewModel()
        val gate = CountDownLatch(1).also { writeGate = it }

        vm.tickItem(MILK)

        assertEquals(listOf(milk(ticked = true), milk()), rows(vm))
        gate.countDown()
        awaitWrites(1)
        // The refetch brings back what the optimistic update already showed.
        awaitValue(vm.state) { (it.result as? AlfredResult.Fresh)?.data == vault.toList() }
        assertEquals(listOf(milk(ticked = true), milk()), rows(vm))
    }

    @Test
    fun `a second tick on the same line crosses the second row`() {
        vault += listOf(milk(), milk())
        val vm = loadedViewModel()
        val gate = CountDownLatch(1).also { writeGate = it }

        vm.tickItem(MILK)
        vm.tickItem(MILK)

        assertEquals(listOf(milk(ticked = true), milk(ticked = true)), rows(vm))
        gate.countDown()
        awaitWrites(2)
        awaitValue(vm.state) { state ->
            (state.result as? AlfredResult.Fresh)?.data == listOf(milk(ticked = true), milk(ticked = true))
        }
    }

    @Test
    fun `a drop on two identical rows removes one`() {
        vault += listOf(milk(), milk())
        val vm = loadedViewModel()
        val gate = CountDownLatch(1).also { writeGate = it }

        vm.dropItem(MILK)

        assertEquals(listOf(milk()), rows(vm))
        gate.countDown()
        awaitWrites(1)
        assertEquals(listOf(milk()), vault.toList())
    }

    @Test
    fun `dropping an open row leaves a ticked row with the same text alone`() {
        vault += listOf(milk(ticked = true), milk())
        val vm = loadedViewModel()
        val gate = CountDownLatch(1).also { writeGate = it }

        vm.dropItem(MILK)

        assertEquals(listOf(milk(ticked = true)), rows(vm))
        gate.countDown()
        awaitWrites(1)
        assertEquals(listOf(milk(ticked = true)), vault.toList())
    }

    @Test
    fun `a drop that matches nothing changes nothing on screen`() {
        vault += milk()
        val vm = loadedViewModel()
        val gate = CountDownLatch(1).also { writeGate = it }

        vm.dropItem(MILK)
        vm.dropItem("- [ ] Rye bread")

        assertFalse(rows(vm).any { it.text == "Rye bread" })
        assertTrue(rows(vm).isEmpty())
        gate.countDown()
    }

    private companion object {
        const val LOCALHOST = "127.0.0.1"
        const val LIST = "groceries"
        const val MILK = "- [ ] Milk"
        const val TICKED_MILK = "- [x] Milk"
    }
}
