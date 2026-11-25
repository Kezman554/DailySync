package com.personalmorningalarm.data.phone

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.time.LocalDate
import java.time.LocalTime

/** Lists and notes kept on the phone: the same operations as the hub's, in Room. */
@RunWith(RobolectricTestRunner::class)
class PhoneListStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: PhoneListDatabase
    /** The store's real scope: one write at a time, in order, outliving any screen. */
    private val writeScope = PhoneListStore.defaultScope()
    private var today: LocalDate = LocalDate.of(2026, 3, 14)
    /** The time of day on [today]: midday, well clear of the 03:07 clear, unless a test moves it. */
    private var time: LocalTime = LocalTime.NOON
    private var now = 1_000L
    private lateinit var store: PhoneListStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, PhoneListDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = PhoneListStore(db.dao(), writeScope, now = { today.atTime(time) }, clock = { now++ })
    }

    @After
    fun tearDown() {
        writeScope.cancel()
        db.close()
    }

    private fun created(title: String): PhoneList = runBlocking {
        (store.createShoppingList(title) as PhoneListStore.CreateResult.Created).list
    }

    private fun items(listId: String) = runBlocking { store.items(listId).first() }

    private fun lists() = runBlocking { store.shoppingLists().first() }

    private fun Job.done() = runBlocking { join() }

    @Test
    fun `a new shopping list is kept with its name trimmed, and lists oldest first`() {
        created("  Camping  ")
        created("Hardware")

        assertEquals(listOf("Camping", "Hardware"), lists().map { it.title })
        assertEquals(listOf(0, 0), lists().map { it.total })
    }

    @Test
    fun `a name already used by a phone list is refused, whatever its case, and a blank one too`() = runBlocking {
        created("Camping")

        assertEquals(PhoneListStore.CreateResult.Conflict, store.createShoppingList(" camping "))
        assertEquals(PhoneListStore.CreateResult.Blank, store.createShoppingList("   "))
        assertEquals(1, lists().size)
    }

    @Test
    fun `add puts an item at the end, as one line, and ignores a blank one`() {
        val list = created("Camping")

        store.add(list.id, "Tent pegs").done()
        store.add(list.id, "Gas\ncanister\r\n").done()
        store.add(list.id, "  \n ").done()

        assertEquals(listOf("Tent pegs", "Gas canister"), items(list.id).map { it.text })
    }

    @Test
    fun `tick lands on the row tapped, even when another has the same text`() {
        val list = created("Camping")
        store.add(list.id, "Batteries").done()
        store.add(list.id, "Batteries").done()
        val second = items(list.id)[1]

        store.tick(second.id).done()

        assertEquals(listOf(false, true), items(list.id).map { it.ticked })
        assertEquals(PhoneListSummary(list.id, "Camping", total = 2, left = 1), lists().single())
    }

    @Test
    fun `drop removes only the row tapped`() {
        val list = created("Camping")
        store.add(list.id, "Batteries").done()
        store.add(list.id, "Batteries").done()
        val first = items(list.id)[0]

        store.drop(first.id).done()

        assertEquals(listOf("Batteries"), items(list.id).map { it.text })
        assertFalse(items(list.id).single().id == first.id)
    }

    @Test
    fun `a ticked item stays, struck through, for the day, and is gone the next`() {
        val list = created("Camping")
        store.add(list.id, "Matches").done()
        store.add(list.id, "Rope").done()
        store.tick(items(list.id)[0].id).done()

        assertEquals(2, items(list.id).size)

        today = today.plusDays(1)

        assertEquals(listOf("Rope"), items(list.id).map { it.text })
        assertEquals(PhoneListSummary(list.id, "Camping", total = 1, left = 1), lists().single())
        // The sweep only tidies what the read already hides.
        store.sweep().done()
        assertEquals(listOf("Rope"), runBlocking { db.dao().allItems(list.id) }.map { it.text })
    }

    @Test
    fun `a tick late at night stays greyed until the 0307 clear, and is gone from then`() {
        val list = created("Camping")
        store.add(list.id, "Matches").done()
        time = LocalTime.of(23, 30)
        store.tick(items(list.id).single().id).done()

        today = today.plusDays(1)
        time = LocalTime.of(3, 6)
        assertTrue(items(list.id).single().ticked)

        time = LocalTime.of(3, 7)
        assertTrue(items(list.id).isEmpty())
        store.sweep().done()
        assertTrue(runBlocking { db.dao().allItems(list.id) }.isEmpty())
    }

    @Test
    fun `a tick in the small hours clears at 0307 the same night`() {
        store.addTodo("Water the ferns").done()
        time = LocalTime.of(1, 0)
        store.tick(runBlocking { store.todoItems().first() }.single().id).done()

        time = LocalTime.of(3, 6)
        assertEquals(1, runBlocking { store.todoItems().first() }.size)
        // The early sweep leaves it: it was ticked in the current sweep day.
        store.sweep().done()
        assertEquals(1, runBlocking { db.dao().allItems(PhoneListStore.TODO_LIST_ID) }.size)

        time = LocalTime.of(3, 7)
        assertTrue(runBlocking { store.todoItems().first() }.isEmpty())
    }

    @Test
    fun `the sweep day turns over at 0307, not midnight`() {
        val day = LocalDate.of(2026, 11, 25)
        assertEquals(day.minusDays(1), PhoneListStore.sweepDayOf(day.atTime(0, 0)))
        assertEquals(day.minusDays(1), PhoneListStore.sweepDayOf(day.atTime(3, 6, 59)))
        assertEquals(day, PhoneListStore.sweepDayOf(day.atTime(3, 7)))
        assertEquals(day, PhoneListStore.sweepDayOf(day.atTime(23, 59)))
    }

    @Test
    fun `ticking twice keeps the day it was first ticked`() {
        val list = created("Camping")
        store.add(list.id, "Matches").done()
        val id = items(list.id).single().id
        store.tick(id).done()

        today = today.plusDays(1)
        store.tick(id).done()

        assertTrue(items(list.id).isEmpty())
    }

    @Test
    fun `deleting a list takes its items with it and leaves the others alone`() {
        val camping = created("Camping")
        val hardware = created("Hardware")
        store.add(camping.id, "Tent pegs").done()
        store.add(hardware.id, "Wood screws").done()

        store.deleteList(camping.id).done()

        assertEquals(listOf("Hardware"), lists().map { it.title })
        assertTrue(runBlocking { db.dao().allItems(camping.id) }.isEmpty())
        assertEquals(listOf("Wood screws"), items(hardware.id).map { it.text })
    }

    @Test
    fun `the to-do is made on its first add, dated that day, and isn't a shopping list`() {
        assertTrue(runBlocking { store.todoItems().first() }.isEmpty())

        store.addTodo("Oil the gate").done()
        store.addTodo("Fix the latch").done()

        val todo = runBlocking { store.todoItems().first() }
        assertEquals(listOf("Oil the gate", "Fix the latch"), todo.map { it.text })
        assertEquals(today.toEpochDay(), todo.first().addedOnEpochDay)
        assertTrue(lists().isEmpty())
    }

    @Test
    fun `a note is kept exactly as typed, replaced by a later save, and deleted`() = runBlocking {
        val text = "Boiler questions\n\n  pressure drop?  \n"
        store.saveNote("n1", text).join()
        store.saveNote("n2", "Second").join()

        assertEquals(text, store.note("n1")?.text)
        assertEquals(listOf("n2", "n1"), store.notes().first().map { it.id })

        store.saveNote("n1", "Boiler questions, answered").join()
        assertEquals("Boiler questions, answered", store.note("n1")?.text)
        assertEquals(listOf("n1", "n2"), store.notes().first().map { it.id })

        store.deleteNote("n1").join()
        assertNull(store.note("n1"))
        assertEquals(listOf("n2"), store.notes().first().map { it.id })
    }

    @Test
    fun `a write finishes even when the screen that asked for it is gone`() = runBlocking {
        val list = created("Camping")
        // The screen's scope, cancelled straight after the tap, as Back does.
        val screen = CoroutineScope(Job())
        screen.launch { store.add(list.id, "Tent pegs") }.join()
        screen.cancel()

        store.sweep().join() // writes land in the order asked, so the add is in by now
        assertEquals(listOf("Tent pegs"), items(list.id).map { it.text })
    }

    @Test
    fun `quick writes land in the order they were asked for`() = runBlocking {
        val list = created("Camping")

        val writes = (1..20).map { store.add(list.id, "Item $it") }
        writes.forEach { it.join() }

        assertEquals((1..20).map { "Item $it" }, items(list.id).map { it.text })
    }

    @Test
    fun `two creates of the same name at once make one list`() = runBlocking {
        val results = (1..5).map { async(Dispatchers.IO) { store.createShoppingList("Camping") } }.map { it.await() }

        assertEquals(1, results.count { it is PhoneListStore.CreateResult.Created })
        assertEquals(1, lists().size)
    }
}
