package com.personalmorningalarm.data.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.entity.ShoppingVerb
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.phone.PhoneListDatabase
import com.personalmorningalarm.data.phone.PhoneListStore
import com.personalmorningalarm.data.phone.ShoppingMoveStore
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.remote.FakeShoppingHub.Item
import kotlinx.coroutines.Job
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
import java.time.LocalDateTime

/**
 * Moving a shopping list between the phone and the hub, against a fake hub over real
 * HTTP. The rule under test: it moves, never copies, and no item is lost whichever
 * step the network (or the phone) fails at.
 */
@RunWith(RobolectricTestRunner::class)
class ShoppingShareTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val hub = FakeShoppingHub()
    private var me: Profile? = Profile.JESS

    private lateinit var phoneDb: PhoneListDatabase
    private lateinit var appDb: AppDatabase
    private val writeScope = PhoneListStore.defaultScope()
    private lateinit var store: PhoneListStore
    private lateinit var flaky: FlakyStore
    private lateinit var sync: ShoppingSync
    private lateinit var share: ShoppingShare

    /** The real store, failing once at a chosen step, as a killed app or a full disk would. */
    class FlakyStore(private val real: ShoppingMoveStore) : ShoppingMoveStore by real {
        var failFinishShare = false
        var failRecordUpload = false

        override suspend fun finishShare(listId: String) {
            if (failFinishShare) {
                failFinishShare = false
                throw IllegalStateException("app killed before the phone copy was deleted")
            }
            real.finishShare(listId)
        }

        override suspend fun recordUpload(listId: String, hubId: String) {
            if (failRecordUpload) {
                failRecordUpload = false
                throw IllegalStateException("app killed before the upload was written down")
            }
            real.recordUpload(listId, hubId)
        }
    }

    @Before
    fun setUp() {
        hub.start()
        AlfredSettings(context).setHost(hub.host)
        val alfred = AlfredRepository(
            AlfredSettings(context),
            AlfredResponseCache(context),
            { AlfredClient.service(it) { me } }
        )
        phoneDb = Room.inMemoryDatabaseBuilder(context, PhoneListDatabase::class.java).allowMainThreadQueries().build()
        appDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        store = PhoneListStore(phoneDb.dao(), writeScope, now = { LocalDateTime.of(2026, 11, 25, 12, 0) })
        flaky = FlakyStore(store)
        sync = ShoppingSync(alfred, appDb.pendingShoppingWriteDao())
        share = ShoppingShare(alfred, sync, flaky) { me }
    }

    @After
    fun tearDown() {
        writeScope.cancel()
        phoneDb.close()
        appDb.close()
        hub.shutdown()
        AlfredReachability.reset()
    }

    // --- helpers ---

    /** A phone list called [title] holding [items], the first [ticked] of them ticked. */
    private fun phoneList(title: String, vararg items: String, ticked: Int = 0): String = runBlocking {
        val list = (store.createShoppingList(title) as PhoneListStore.CreateResult.Created).list
        items.forEach { store.add(list.id, it).join() }
        store.items(list.id).first().take(ticked).forEach { store.tick(it.id).join() }
        list.id
    }

    /** What the menu shows from the phone: title to its items, ticks marked "[x] ". */
    private fun shownOnPhone(): Map<String, List<String>> = runBlocking {
        store.shoppingLists().first().associate { summary ->
            summary.title to store.items(summary.id).first().map { (if (it.ticked) "[x] " else "") + it.text }
        }
    }

    private fun onHub(): Map<String, List<String>> =
        hub.lists.values.associate { list -> list.title to list.items.map { (if (it.ticked) "[x] " else "") + it.text } }

    private fun summaryOf(list: FakeShoppingHub.HubList) =
        ShoppingListSummaryDto(list.id, list.title, list.items.size, list.items.count { !it.ticked }, list.owner, list.shared)

    private fun moves() = runBlocking { store.moves() }

    // --- phone to hub ---

    @Test
    fun `sharing moves a phone list to the hub, ticks kept, owned by whoever shared it`() = runBlocking {
        val id = phoneList("Big shop", "Milk", "Eggs", ticked = 1)

        val outcome = share.sharePhoneList(id)

        val moved = outcome as ShoppingShare.Outcome.MovedToHub
        val list = hub.lists.getValue(moved.hubId)
        assertEquals(mapOf("Big shop" to listOf("[x] Milk", "Eggs")), onHub())
        assertEquals("jess", list.owner)
        assertTrue(list.shared)
        // Moved, not copied.
        assertTrue(shownOnPhone().isEmpty())
        assertNull(store.shareSnapshot(id))
        assertTrue(moves().isEmpty())
    }

    @Test
    fun `with no hub, sharing says so and leaves the phone list as it was, still editable`() = runBlocking {
        val id = phoneList("Big shop", "Milk")
        hub.down = true

        assertEquals(ShoppingShare.Outcome.Unreachable, share.sharePhoneList(id))

        assertEquals(0, hub.uploads)
        assertTrue(moves().isEmpty())
        store.add(id, "Bread").join()
        assertEquals(mapOf("Big shop" to listOf("Milk", "Bread")), shownOnPhone())
    }

    @Test
    fun `a failure after the upload and before the local delete still moves it, never twice`() = runBlocking {
        val id = phoneList("Big shop", "Milk", "Eggs")
        flaky.failFinishShare = true

        val outcome = share.sharePhoneList(id)

        // The hub has it, so it has moved: the leftover phone copy is hidden, not shown twice.
        assertTrue(outcome is ShoppingShare.Outcome.MovedToHub)
        assertEquals(mapOf("Big shop" to listOf("Milk", "Eggs")), onHub())
        assertTrue(shownOnPhone().isEmpty())
        assertEquals(1, moves().size)

        share.resume()

        assertTrue(moves().isEmpty())
        assertNull(store.shareSnapshot(id))
        assertEquals(1, hub.uploads)
    }

    @Test
    fun `killed before the upload was written down, resume finds the upload rather than repeating it`() = runBlocking {
        val id = phoneList("Big shop", "Milk")
        flaky.failRecordUpload = true

        assertEquals(ShoppingShare.Outcome.Unfinished, share.sharePhoneList(id))
        // Until it's settled the phone copy shows, and takes no edits that could drift.
        store.add(id, "Bread").join()
        assertEquals(mapOf("Big shop" to listOf("Milk")), shownOnPhone())

        share.resume()

        assertEquals(1, hub.uploads)
        assertEquals(mapOf("Big shop" to listOf("Milk")), onHub())
        assertTrue(shownOnPhone().isEmpty())
        assertTrue(moves().isEmpty())
    }

    @Test
    fun `an upload whose answer is lost is found on the hub, not uploaded again`() = runBlocking {
        val id = phoneList("Big shop", "Milk")
        hub.loseAnswerTo += "upload"

        val outcome = share.sharePhoneList(id)

        assertTrue(outcome is ShoppingShare.Outcome.MovedToHub)
        assertEquals(1, hub.uploads)
        assertEquals(1, hub.lists.size)
        assertTrue(shownOnPhone().isEmpty())
    }

    @Test
    fun `a lost upload answer with the hub then gone waits, locked, and settles when it's back`() = runBlocking {
        val id = phoneList("Big shop", "Milk")
        hub.loseAnswerTo += "upload"
        hub.goDownAfter += "upload"

        val outcome = share.sharePhoneList(id)

        assertEquals(ShoppingShare.Outcome.Unfinished, outcome)
        assertEquals(mapOf("Big shop" to listOf("Milk")), shownOnPhone())

        hub.down = false
        share.resume()

        assertEquals(1, hub.uploads)
        assertEquals(mapOf("Big shop" to listOf("Milk")), onHub())
        assertTrue(shownOnPhone().isEmpty())
    }

    @Test
    fun `an upload the hub refuses leaves the phone list as it was, unlocked`() = runBlocking {
        val id = phoneList("Big shop", "Milk")
        hub.refuseUploads = true

        assertEquals(ShoppingShare.Outcome.Refused, share.sharePhoneList(id))

        assertTrue(hub.lists.isEmpty())
        assertTrue(moves().isEmpty())
        store.add(id, "Bread").join()
        assertEquals(mapOf("Big shop" to listOf("Milk", "Bread")), shownOnPhone())
    }

    // --- hub to phone ---

    @Test
    fun `stopping sharing pulls Jess's list back to her phone, ticks kept, and the hub copy goes`() = runBlocking {
        val list = hub.add("Big shop", "jess", shared = true, Item("Milk", true), Item("Eggs", false))

        val outcome = share.setShared(summaryOf(list), shared = false)

        val moved = outcome as ShoppingShare.Outcome.MovedToPhone
        assertTrue(moved.hubConfirmedGone)
        assertTrue(hub.lists.isEmpty())
        assertEquals(mapOf("Big shop" to listOf("[x] Milk", "Eggs")), shownOnPhone())
        assertTrue(moves().isEmpty())
    }

    @Test
    fun `a pull-back whose answer is lost keeps the items it read first`() = runBlocking {
        val list = hub.add("Big shop", "jess", shared = true, Item("Milk", false), Item("Eggs", false))
        hub.loseAnswerTo += "pull-back"

        val outcome = share.setShared(summaryOf(list), shared = false)

        assertTrue(outcome is ShoppingShare.Outcome.MovedToPhone)
        assertTrue(hub.lists.isEmpty())
        assertEquals(mapOf("Big shop" to listOf("Milk", "Eggs")), shownOnPhone())
        assertTrue(moves().isEmpty())
    }

    @Test
    fun `a pull-back cut off with the hub gone loses nothing, and finishes when it's back`() = runBlocking {
        val list = hub.add("Big shop", "jess", shared = true, Item("Milk", false))
        hub.loseAnswerTo += "pull-back"
        hub.goDownAfter += "pull-back"

        val outcome = share.setShared(summaryOf(list), shared = false)

        assertEquals(ShoppingShare.Outcome.Unfinished, outcome)
        // The items are on the phone, waiting; the list shows once it's settled.
        assertEquals(1, moves().size)
        assertTrue(shownOnPhone().isEmpty())

        hub.down = false
        share.resume()

        assertEquals(mapOf("Big shop" to listOf("Milk")), shownOnPhone())
        assertTrue(moves().isEmpty())
    }

    @Test
    fun `with no hub, stopping sharing says so and nothing moves`() = runBlocking {
        val list = hub.add("Big shop", "jess", shared = true, Item("Milk", false))
        hub.down = true

        assertEquals(ShoppingShare.Outcome.Unreachable, share.setShared(summaryOf(list), shared = false))

        hub.down = false
        assertEquals(1, hub.lists.size)
        assertTrue(shownOnPhone().isEmpty())
        assertTrue(moves().isEmpty())
    }

    @Test
    fun `a pull-back waits while edits to the list are still queued`() = runBlocking {
        val list = hub.add("Big shop", "jess", shared = true, Item("Milk", false))
        sync.enqueue(list.id, ShoppingVerb.ADD, "Bread")
        hub.addsFail = true

        assertEquals(ShoppingShare.Outcome.ChangesWaiting, share.setShared(summaryOf(list), shared = false))

        assertEquals(1, hub.lists.size)
        assertTrue(shownOnPhone().isEmpty())
    }

    @Test
    fun `queued edits are delivered first, then the list comes back with them`() = runBlocking {
        val list = hub.add("Big shop", "jess", shared = true, Item("Milk", false))
        sync.enqueue(list.id, ShoppingVerb.ADD, "Bread")

        assertTrue(share.setShared(summaryOf(list), shared = false) is ShoppingShare.Outcome.MovedToPhone)

        assertEquals(mapOf("Big shop" to listOf("Milk", "Bread")), shownOnPhone())
        assertTrue(!sync.hasPending())
    }

    // --- the flag, and whose list it is ---

    @Test
    fun `Nick stopping sharing his own list only clears the flag, and it stays on the hub`() = runBlocking {
        me = Profile.NICK
        val list = hub.add("Hardware", "nick", shared = true, Item("Screws", false))

        assertEquals(ShoppingShare.Outcome.FlagSet(false), share.setShared(summaryOf(list), shared = false))

        assertEquals(false, hub.lists.getValue(list.id).shared)
        assertEquals(1, hub.lists.size)
        assertTrue(shownOnPhone().isEmpty())
    }

    @Test
    fun `sharing a hub list of your own sets the flag`() = runBlocking {
        me = Profile.NICK
        val list = hub.add("Hardware", "nick", shared = false)

        assertEquals(ShoppingShare.Outcome.FlagSet(true), share.setShared(summaryOf(list), shared = true))

        assertTrue(hub.lists.getValue(list.id).shared)
    }

    @Test
    fun `only the owner can stop sharing, the other person can't, in either direction`() = runBlocking {
        val nicks = hub.add("Hardware", "nick", shared = true, Item("Screws", false))
        me = Profile.JESS
        assertEquals(ShoppingShare.Outcome.NotOwner, share.setShared(summaryOf(nicks), shared = false))

        val jesss = hub.add("Big shop", "jess", shared = true, Item("Milk", false))
        me = Profile.NICK
        assertEquals(ShoppingShare.Outcome.NotOwner, share.setShared(summaryOf(jesss), shared = false))

        assertTrue(hub.lists.values.all { it.shared })
        assertTrue(hub.requests.none { it.endsWith("/share") || it.endsWith("/pull-back") })
    }
}
