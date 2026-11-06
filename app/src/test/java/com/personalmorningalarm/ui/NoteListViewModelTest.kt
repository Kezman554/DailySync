package com.personalmorningalarm.ui

import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.model.NoteRow
import com.personalmorningalarm.data.model.Notes
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResponseCache
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.AlfredSettings
import com.personalmorningalarm.data.remote.FakeNotesServer
import com.personalmorningalarm.data.remote.NoteSync
import com.personalmorningalarm.data.remote.dataOrNull
import com.personalmorningalarm.ui.NoteListViewModel.NoteListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The list as the screen renders it: Alfred's notes, the saved copy, and the queue. */
@RunWith(RobolectricTestRunner::class)
class NoteListViewModelTest : ViewModelTestSupport() {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val settings = AlfredSettings(context)
    private val cache = AlfredResponseCache(context)
    private val server = FakeNotesServer()

    private val alfred by lazy { AlfredRepository(settings, cache) }
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sync by lazy { NoteSync(alfred, db.pendingNoteWriteDao(), writeScope) }

    @Before
    fun setUp() {
        server.start(settings)
    }

    @After
    fun tearDown() {
        writeScope.cancel()
        server.shutdown()
        AlfredReachability.reset()
    }

    private fun list() = track(NoteListViewModel(alfred, sync))

    private fun rows(state: NoteListState): List<NoteRow> =
        Notes.merged(state.result?.dataOrNull().orEmpty(), state.pending)

    private fun loaded(vm: NoteListViewModel) = awaitValue(vm.state) { !it.loading && it.result != null }

    @Test
    fun `Alfred's notes list newest first`() {
        server.notes[OLDER] = "Older\nnote"
        server.notes[NEWER] = "Newer"

        val state = loaded(list())

        assertTrue(state.result is AlfredResult.Fresh)
        assertEquals(listOf("Newer", "Older"), rows(state).map { it.title })
    }

    @Test
    fun `with Alfred gone the list falls back to the saved copy and says how old it is`() {
        server.notes[OLDER] = "Saved"
        loaded(list())
        server.down = true

        val state = loaded(list())

        assertTrue(state.result is AlfredResult.Stale)
        assertEquals(listOf("Saved"), rows(state).map { it.title })
        val age = Freshness.of(state.result, System.currentTimeMillis(), ZoneId.systemDefault())
        assertTrue(Freshness.wantsRetry(age))
    }

    @Test
    fun `a note queued offline shows straight away, even with nothing saved`() {
        server.down = true
        val vm = list()
        loaded(vm)

        sync.save(NEWER, "Written offline")

        val state = awaitValue(vm.state) { it.pending.isNotEmpty() }
        assertEquals(AlfredResult.Unavailable, state.result)
        assertEquals(listOf(NoteRow(NEWER, "Written offline", "", pending = true)), rows(state))
    }

    @Test
    fun `a delete made offline leaves the list at once`() {
        server.notes[OLDER] = "Going"
        val vm = list()
        loaded(vm)
        server.down = true

        vm.delete(OLDER)

        assertEquals(emptyList<NoteRow>(), rows(awaitValue(vm.state) { it.pending.isNotEmpty() }))
    }

    @Test
    fun `a delivered note stays listed when the refetch fails`() {
        server.notes[OLDER] = "Already here"
        val vm = list()
        loaded(vm)
        val fetchedAt = cache.get(AlfredRepository.ENDPOINT_NOTES)!!.cachedAtMillis
        // The save lands, then Alfred goes before the list can ask again.
        server.readsDown = true

        sync.save(NEWER, "Delivered")

        val state = awaitValue(vm.state) { it.result is AlfredResult.Stale && it.pending.isEmpty() }
        assertEquals(listOf("Delivered", "Already here"), rows(state).map { it.title })
        assertEquals("the rest of the list is no newer", fetchedAt, (state.result as AlfredResult.Stale).cachedAtMillis)
    }

    @Test
    fun `a delivered delete doesn't bring the note back from the saved copy`() {
        server.notes[OLDER] = "Going"
        val vm = list()
        loaded(vm)

        vm.delete(OLDER)
        awaitValue(vm.state) { it.pending.isEmpty() && rows(it).isEmpty() }
        server.down = true

        val state = awaitValue(list().state) { it.result is AlfredResult.Stale }

        assertEquals(emptyList<NoteRow>(), rows(state))
    }

    @Test
    fun `a deleted note stays gone while the rest of the queue is still sending`() {
        server.notes[OLDER] = "Going"
        val vm = list()
        loaded(vm)
        server.down = true
        vm.delete(OLDER)
        sync.save(NEWER, "Slow to send")
        val gate = CountDownLatch(1)
        val arrived = CountDownLatch(1)
        server.gateOnlyFor = NEWER
        server.writeGate = gate
        server.writeArrived = arrived
        server.down = false

        val flush = CoroutineScope(Dispatchers.IO).launch { sync.flush() }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))

        // The delete is delivered and its row gone; the flush, and so the refetch, isn't done.
        val state = awaitValue(vm.state) { it.pending.size == 1 }
        assertEquals(listOf(NEWER), rows(state).map { it.id })
        gate.countDown()
        runBlocking { flush.join() }
    }

    @Test
    fun `a flush elsewhere brings the list up to date`() {
        val vm = list()
        loaded(vm)
        server.notes[OLDER] = "Added in the vault"

        sync.save(NEWER, "Written here")

        val state = awaitValue(vm.state) { rows(it).size == 2 && it.pending.isEmpty() }
        assertEquals(listOf("Written here", "Added in the vault"), rows(state).map { it.title })
    }

    @Test
    fun `a refresh sends what's queued before it reads`() {
        server.down = true
        sync.save(NEWER, "Queued first")
        server.down = false

        val state = awaitValue(list().state) { it.result is AlfredResult.Fresh && it.pending.isEmpty() }

        assertEquals(listOf(NoteRow(NEWER, "Queued first", "")), rows(state))
    }

    private companion object {
        const val OLDER = "11111111-2222-4333-8444-555555555555"
        const val NEWER = "66666666-7777-4888-8999-aaaaaaaaaaaa"
    }
}
