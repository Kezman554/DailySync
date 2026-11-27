package com.personalmorningalarm.data.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.model.ListMerge.Counts
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.phone.ListHome
import com.personalmorningalarm.data.phone.PhoneListDatabase
import com.personalmorningalarm.data.phone.PhoneListStore
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.remote.FakeShoppingHub.Item
import com.personalmorningalarm.data.remote.ShoppingCopy.Outcome
import com.personalmorningalarm.data.remote.ShoppingCopy.Target
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

/**
 * "Copy into…" against a fake hub over real HTTP and a real phone store: the merge
 * rule carried out on each store, and a Pi merge that stops part-way finished by
 * copying again, with nothing doubled.
 */
@RunWith(RobolectricTestRunner::class)
class ShoppingCopyTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val hub = FakeShoppingHub()
    private var me: Profile? = Profile.NICK

    private lateinit var phoneDb: PhoneListDatabase
    private val writeScope = PhoneListStore.defaultScope()
    private lateinit var store: PhoneListStore
    private lateinit var copy: ShoppingCopy

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
        store = PhoneListStore(phoneDb.dao(), writeScope, now = { LocalDateTime.of(2026, 11, 27, 12, 0) })
        copy = ShoppingCopy(alfred, store) { me }
    }

    @After
    fun tearDown() {
        writeScope.cancel()
        phoneDb.close()
        hub.shutdown()
        AlfredReachability.reset()
    }

    // --- helpers ---

    private fun summaryOf(list: FakeShoppingHub.HubList) =
        ShoppingListSummaryDto(list.id, list.title, list.items.size, list.items.count { !it.ticked }, list.owner, list.shared)

    private fun shown(list: FakeShoppingHub.HubList): List<String> =
        list.items.map { (if (it.ticked) "[x] " else "") + it.text }

    private fun shownOnPhone(listId: String): List<String> = runBlocking {
        store.items(listId).first().map { (if (it.ticked) "[x] " else "") + it.text }
    }

    /** Jess's list, shared with Nick: one bought, two still to get. */
    private fun jessesList() = hub.add(
        "Oliver's Christmas", "jess", shared = true,
        Item("Lego set", ticked = true), Item("Book", false), Item("Socks", false)
    )

    // --- into a Pi list (Nick) ---

    @Test
    fun `a merge into your own Pi list adds what's missing, ticked if bought, and says so`() = runBlocking {
        val theirs = jessesList()
        val mine = hub.add("Gifts", "nick", shared = false, Item("book", false), Item("Pen", false))

        val outcome = copy.copy(summaryOf(theirs), Target.Hub(mine.id, mine.title))

        val done = outcome as Outcome.Done
        assertEquals(Counts(added = 1, alreadyThere = 1, alreadyBought = 1), done.counts)
        assertEquals(Profile.JESS, done.sharer)
        assertEquals(ListHome.HUB, done.home)
        assertEquals(listOf("book", "Pen", "[x] Lego set", "Socks"), shown(mine))
        // Theirs is only read.
        assertEquals(listOf("[x] Lego set", "Book", "Socks"), shown(theirs))
        assertFalse(mine.shared)
    }

    @Test
    fun `an item they've bought that's on yours unticked is ticked there, and nothing is unticked`() = runBlocking {
        val theirs = jessesList()
        val mine = hub.add("Gifts", "nick", shared = false, Item("LEGO  set", false), Item("Socks", true))

        val done = copy.copy(summaryOf(theirs), Target.Hub(mine.id, mine.title)) as Outcome.Done

        assertEquals(listOf("[x] LEGO  set", "[x] Socks", "Book"), shown(mine))
        assertEquals(Counts(added = 1, alreadyThere = 1, alreadyBought = 1), done.counts)
    }

    @Test
    fun `a new list for Nick is made on the Pi, his and shared with nobody`() = runBlocking {
        val theirs = jessesList()

        val done = copy.copy(summaryOf(theirs), Target.NewList("Oliver's Christmas (mine)")) as Outcome.Done

        val made = hub.lists.getValue(done.listId)
        assertEquals("nick", made.owner)
        assertFalse(made.shared)
        assertEquals(listOf("[x] Lego set", "Book", "Socks"), shown(made))
        assertEquals(Counts(added = 2, alreadyThere = 0, alreadyBought = 1), done.counts)
    }

    @Test
    fun `a new list whose name you've used already is refused, and nothing is made`() = runBlocking {
        val theirs = jessesList()
        hub.add("Gifts", "nick", shared = false)

        assertEquals(Outcome.NameTaken, copy.copy(summaryOf(theirs), Target.NewList("Gifts")))
        assertEquals(2, hub.lists.size)
    }

    @Test
    fun `a second merge brings only what's new since, and their deletions leave yours alone`() = runBlocking {
        val theirs = jessesList()
        val mine = hub.add("Gifts", "nick", shared = false)
        copy.copy(summaryOf(theirs), Target.Hub(mine.id, mine.title))
        // Jess buys the book, deletes the socks and adds a scarf.
        theirs.items.clear()
        theirs.items += listOf(Item("Lego set", true), Item("Book", true), Item("Scarf", false))

        val done = copy.copy(summaryOf(theirs), Target.Hub(mine.id, mine.title)) as Outcome.Done

        assertEquals(listOf("[x] Lego set", "[x] Book", "Socks", "Scarf"), shown(mine))
        assertEquals(Counts(added = 1, alreadyThere = 1, alreadyBought = 1), done.counts)
    }

    @Test
    fun `a merge cut off part-way is finished by copying again, with nothing doubled`() = runBlocking {
        val theirs = hub.add(
            "Oliver's Christmas", "jess", shared = true,
            Item("Lego set", true), Item("Book", false), Item("Socks", false), Item("Torch", true)
        )
        val mine = hub.add("Gifts", "nick", shared = false, Item("Pen", false))
        hub.downAfterAdds = 2

        val first = copy.copy(summaryOf(theirs), Target.Hub(mine.id, mine.title))

        assertEquals(Outcome.Unfinished(mine.title, mine.id), first)
        assertEquals(listOf("Pen", "Lego set", "Book"), shown(mine))

        hub.down = false
        hub.downAfterAdds = null
        val second = copy.copy(summaryOf(theirs), Target.Hub(mine.id, mine.title)) as Outcome.Done

        assertEquals(listOf("Pen", "[x] Lego set", "Book", "Socks", "[x] Torch"), shown(mine))
        // The first attempt's story, not "Added 0".
        assertEquals(Counts(added = 2, alreadyThere = 0, alreadyBought = 2), second.counts)

        // And once more changes nothing at all.
        val third = copy.copy(summaryOf(theirs), Target.Hub(mine.id, mine.title)) as Outcome.Done
        assertEquals(listOf("Pen", "[x] Lego set", "Book", "Socks", "[x] Torch"), shown(mine))
        assertEquals(Counts(added = 0, alreadyThere = 4, alreadyBought = 0), third.counts)
    }

    @Test
    fun `an add whose answer is lost is sent again under the same key and lands once`() = runBlocking {
        val theirs = hub.add("Oliver's Christmas", "jess", shared = true, Item("Book", false))
        val mine = hub.add("Gifts", "nick", shared = false)
        hub.loseNextAddAnswer = true

        val done = copy.copy(summaryOf(theirs), Target.Hub(mine.id, mine.title))

        assertTrue(done is Outcome.Done)
        assertEquals(listOf("Book"), shown(mine))
        val sent = hub.addRequests.toList()
        assertEquals(2, sent.size)
        assertEquals(1, sent.map { it.substringBefore(':') }.toSet().size)
        assertFalse(sent.first().startsWith("null:"))
    }

    @Test
    fun `with no hub nothing is copied, and the hub isn't asked to change anything`() = runBlocking {
        val theirs = jessesList()
        val mine = hub.add("Gifts", "nick", shared = false)
        hub.down = true

        assertEquals(Outcome.Unreachable, copy.copy(summaryOf(theirs), Target.Hub(mine.id, mine.title)))
        hub.down = false
        assertTrue(mine.items.isEmpty())
    }

    @Test
    fun `Nick's targets are his own lists, never the one being copied or Jess's`() = runBlocking {
        val theirs = jessesList()
        hub.add("Gifts", "nick", shared = false)
        hub.add("DIY", "nick", shared = true)
        hub.add("Jess's other", "jess", shared = true)

        val targets = copy.targets(summaryOf(theirs))

        assertEquals(listOf("Gifts", "DIY"), targets.map { it.title })
        assertTrue(targets.all { it is Target.Hub })
    }

    // --- into a phone list (Jess) ---

    /** Nick's list, shared with Jess. */
    private fun nicksList() = hub.add(
        "Garden", "nick", shared = true,
        Item("Hedge shears", true), Item("Twine", false), Item("Gloves", false)
    )

    @Test
    fun `a new list for Jess is made on her phone, ticks carried, and the hub isn't written to`() = runBlocking {
        me = Profile.JESS
        val theirs = nicksList()
        hub.requests.clear()

        val done = copy.copy(summaryOf(theirs), Target.NewList("Garden (mine)")) as Outcome.Done

        assertEquals(ListHome.PHONE, done.home)
        assertEquals(Profile.NICK, done.sharer)
        assertEquals(listOf("[x] Hedge shears", "Twine", "Gloves"), shownOnPhone(done.listId))
        assertEquals(Counts(added = 2, alreadyThere = 0, alreadyBought = 1), done.counts)
        assertEquals(1, hub.lists.size)
        assertTrue(hub.requests.none { it.startsWith("POST") })
    }

    @Test
    fun `a second merge into Jess's phone list adds only what's new and ticks what's bought`() = runBlocking {
        me = Profile.JESS
        val theirs = nicksList()
        val first = copy.copy(summaryOf(theirs), Target.NewList("Garden (mine)")) as Outcome.Done
        // She adds her own, and Nick buys the twine and adds a trowel.
        store.add(first.listId, "Seeds").join()
        theirs.items[1] = Item("Twine", true)
        theirs.items += Item("Trowel", false)

        val second = copy.copy(summaryOf(theirs), Target.Phone(first.listId, "Garden (mine)")) as Outcome.Done

        assertEquals(listOf("[x] Hedge shears", "[x] Twine", "Gloves", "Seeds", "Trowel"), shownOnPhone(first.listId))
        assertEquals(Counts(added = 1, alreadyThere = 2, alreadyBought = 1), second.counts)
    }

    @Test
    fun `Jess's targets include her phone lists`() = runBlocking {
        me = Profile.JESS
        val theirs = nicksList()
        store.createShoppingList("Camping")

        val targets = copy.targets(summaryOf(theirs))

        assertEquals(listOf("Camping"), targets.map { it.title })
        assertTrue(targets.single() is Target.Phone)
    }

    @Test
    fun `a phone list name already taken is refused`() = runBlocking {
        me = Profile.JESS
        val theirs = nicksList()
        store.createShoppingList("Garden")

        assertEquals(Outcome.NameTaken, copy.copy(summaryOf(theirs), Target.NewList(" garden ")))
    }
}
