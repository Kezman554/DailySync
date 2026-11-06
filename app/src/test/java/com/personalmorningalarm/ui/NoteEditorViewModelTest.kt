package com.personalmorningalarm.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.entity.NoteVerb
import com.personalmorningalarm.data.model.Notes
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResponseCache
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.AlfredSettings
import com.personalmorningalarm.data.remote.FakeNotesServer
import com.personalmorningalarm.data.remote.NoteSync
import com.personalmorningalarm.ui.NoteEditorViewModel.EditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch

/**
 * What leaving the editor does. Alfred is down in most of these, so whatever was
 * written stays in the queue where the test can read it.
 */
@RunWith(RobolectricTestRunner::class)
class NoteEditorViewModelTest : ViewModelTestSupport() {

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

    private fun editor(isNew: Boolean = false, handle: SavedStateHandle = SavedStateHandle()) =
        track(NoteEditorViewModel(alfred, sync, ID, isNew, handle))

    private fun ready(isNew: Boolean = false): NoteEditorViewModel =
        editor(isNew).also { vm -> awaitValue(vm.state) { it is EditorState.Ready } }

    private fun queued() = runBlocking { sync.queue.first() }

    // ---- A new note ----

    @Test
    fun `a new note opens at once and empty, without asking Alfred`() {
        server.down = true

        val vm = editor(isNew = true)

        assertTrue(vm.state.value is EditorState.Ready)
        assertEquals("", vm.draft)
    }

    @Test
    fun `a new note left blank isn't saved`() {
        server.down = true
        val vm = ready(isNew = true)

        assertEquals(Notes.Leaving.NOTHING, vm.leave("   \n"))

        assertTrue(queued().isEmpty())
    }

    @Test
    fun `a new note with text is saved under its own id, exactly as typed`() {
        server.down = true
        val vm = ready(isNew = true)

        assertEquals(Notes.Leaving.SAVE, vm.leave("  Keep the spaces \n"))

        assertEquals(ID, queued().single().noteId)
        assertEquals("  Keep the spaces \n", queued().single().text)
    }

    @Test
    fun `a new note kept in the background, then emptied, is an existing note`() {
        server.down = true
        val vm = ready(isNew = true)
        vm.background("Half a thought")

        assertEquals(Notes.Leaving.CONFIRM_DELETE, vm.leave(""))
    }

    // ---- An existing note ----

    @Test
    fun `an existing note opens on its full text, not the list's preview`() {
        server.notes[ID] = LONG_NOTE

        val vm = ready()

        assertEquals(LONG_NOTE, vm.draft)
    }

    @Test
    fun `leaving an unchanged note writes nothing`() {
        server.notes[ID] = "Unchanged"
        val vm = ready()

        assertEquals(Notes.Leaving.NOTHING, vm.leave("Unchanged"))

        assertTrue(server.writes.isEmpty())
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `an edit is saved, and leaving again doesn't save it twice`() {
        server.notes[ID] = "Before"
        val vm = ready()
        server.down = true

        vm.background("After")
        val row = queued().single()
        vm.leave("After")

        assertEquals("After", row.text)
        assertEquals("a second save would be a new row", row, queued().single())
    }

    @Test
    fun `each stop saves what's there now`() {
        server.notes[ID] = "Before"
        val vm = ready()
        server.down = true

        vm.background("Draft one")
        vm.background("Draft two")

        assertEquals("Draft two", queued().single().text)
    }

    @Test
    fun `a queued edit opens over Alfred's older text`() {
        server.notes[ID] = "Older"
        server.down = true
        sync.save(ID, "Newer")
        server.down = false

        // Still queued as far as this editor is concerned: open it before it's flushed.
        server.writeStatus = 503
        val vm = ready()

        assertEquals("Newer", vm.draft)
    }

    @Test
    fun `a refused note opens from the phone so it can be fixed`() {
        val tooLong = "x".repeat(FakeNotesServer.MAX_CHARS + 1)
        sync.save(ID, tooLong)
        awaitQueueFailed()

        val vm = ready()
        vm.leave("Shorter")

        awaitVault("Shorter")
    }

    private fun awaitQueueFailed() {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && queued().none { it.failed }) Thread.sleep(15)
        assertTrue(queued().single().failed)
    }

    private fun awaitVault(text: String) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && server.notes[ID] != text) Thread.sleep(15)
        assertEquals(text, server.notes[ID])
    }

    // ---- Emptying a note ----

    @Test
    fun `an emptied note asks first and writes nothing yet`() {
        server.notes[ID] = "Keep me?"
        val vm = ready()

        assertEquals(Notes.Leaving.CONFIRM_DELETE, vm.leave("  "))

        assertTrue(server.writes.isEmpty())
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `confirming the delete queues it`() {
        server.notes[ID] = "Delete me"
        val vm = ready()
        server.down = true
        vm.leave("")

        vm.confirmDelete()

        assertEquals(NoteVerb.DELETE.name, queued().single().verb)
    }

    @Test
    fun `the stop that follows a delete doesn't save over it`() {
        server.notes[ID] = "Delete me"
        val vm = ready()
        server.down = true
        vm.leave("")
        vm.confirmDelete()

        vm.background("")
        vm.background("Stale text from the closing screen")

        assertEquals(NoteVerb.DELETE.name, queued().single().verb)
    }

    @Test
    fun `declining puts the text back and writes nothing`() {
        server.notes[ID] = "Keep me"
        val vm = ready()
        vm.leave("")

        assertEquals("Keep me", vm.keepNote())
        assertEquals("Keep me", vm.draft)
        assertEquals(Notes.Leaving.NOTHING, vm.leave("Keep me"))

        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun `an emptied note going to the background is left alone`() {
        server.notes[ID] = "Survives"
        val vm = ready()

        vm.background("")

        assertTrue(server.writes.isEmpty())
        assertTrue(queued().isEmpty())
        assertEquals(Notes.Leaving.CONFIRM_DELETE, vm.leave(""))
    }

    // ---- Text that can't be had ----

    @Test
    fun `with no text to open the editor is shut`() {
        server.down = true

        val vm = editor()
        awaitValue(vm.state) { it is EditorState.Unavailable }

        assertTrue(vm.state.value is EditorState.Unavailable)
    }

    @Test
    fun `an editor that never got the note can't delete or overwrite it`() {
        server.down = true
        val vm = editor()
        awaitValue(vm.state) { it is EditorState.Unavailable }

        assertEquals(Notes.Leaving.NOTHING, vm.leave(""))
        vm.background("")
        assertEquals(Notes.Leaving.NOTHING, vm.leave("Typed somehow"))

        assertTrue(queued().isEmpty())
    }

    @Test
    fun `an editor still loading can't write either`() {
        server.notes[ID] = "Not read yet"
        val held = CountDownLatch(1)
        server.readGate = held
        val vm = editor()
        assertTrue(vm.state.value is EditorState.Loading)

        assertEquals(Notes.Leaving.NOTHING, vm.leave(""))
        assertEquals(Notes.Leaving.NOTHING, vm.leave("Typed somehow"))

        assertTrue(queued().isEmpty())
        held.countDown()
    }

    @Test
    fun `a saved copy opens, and says it's a saved copy`() {
        cache.put(AlfredRepository.noteEndpoint(ID), """{"id":"$ID","text":"Saved text"}""", 1_000L)
        server.down = true

        val vm = ready()

        assertEquals("Saved text", vm.draft)
        assertTrue((vm.state.value as EditorState.Ready).source is AlfredResult.Stale)
    }

    @Test
    fun `an edit to a saved copy queues like any other`() {
        cache.put(AlfredRepository.noteEndpoint(ID), """{"id":"$ID","text":"Saved text"}""", 1_000L)
        server.down = true
        val vm = ready()

        vm.leave("Edited offline")

        assertEquals("Edited offline", queued().single().text)
    }

    // ---- The screen going away ----

    @Test
    fun `the save survives the screen that made it`() {
        server.down = true
        val vm = ready(isNew = true)

        vm.leave("Saved on the way out")
        vm.viewModelScope.cancel()

        assertEquals("Saved on the way out", queued().single().text)
    }

    @Test
    fun `a new note restored after a process death is the same note`() {
        server.down = true
        val handle = SavedStateHandle()
        val first = editor(isNew = true, handle = handle)
        first.background("Written before the kill")

        val restored = editor(isNew = true, handle = handle)
        awaitValue(restored.state) { it is EditorState.Ready }

        assertEquals("Written before the kill", restored.draft)
        assertEquals(Notes.Leaving.NOTHING, restored.leave("Written before the kill"))
        assertEquals(1, queued().size)
    }

    @Test
    fun `a new note delivered before the kill reloads from Alfred`() {
        val handle = SavedStateHandle()
        val first = editor(isNew = true, handle = handle)
        first.background("Delivered")
        awaitVault("Delivered")
        while (queued().isNotEmpty()) Thread.sleep(15)

        val restored = editor(isNew = true, handle = handle)
        awaitValue(restored.state) { it is EditorState.Ready }

        assertEquals("Delivered", restored.draft)
        assertNull(queued().firstOrNull())
    }

    private companion object {
        const val ID = "11111111-2222-4333-8444-555555555555"
        val LONG_NOTE = "First line\n" + "A long middle. ".repeat(40) + "\nLast line"
    }
}
