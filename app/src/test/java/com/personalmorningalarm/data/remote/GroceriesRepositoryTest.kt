package com.personalmorningalarm.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.model.Groceries
import com.personalmorningalarm.data.model.GroceryItemDto
import com.personalmorningalarm.data.model.ShoppingItemDto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Groceries: KitchenSync's grocery list as a shopping source, against a fake
 * KitchenSync over real HTTP. Read, add, tick, remove, and what happens with
 * KitchenSync unreachable.
 */
@RunWith(RobolectricTestRunner::class)
class GroceriesRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val kitchen = FakeKitchenSync()
    private var now = 1_000L
    private lateinit var groceries: GroceriesRepository

    @Before
    fun setUp() {
        kitchen.start()
        AlfredResponseCache(context).clear(GroceriesRepository.CACHE_KEY)
        groceries = GroceriesRepository(
            AlfredResponseCache(context),
            { KitchenSyncClient.service(kitchen.baseUrl) },
            clock = { now }
        )
    }

    @After
    fun tearDown() {
        kitchen.shutdown()
        KitchenSyncReachability.reset()
    }

    private fun shown(): List<String> = runBlocking {
        (groceries.getShoppingList(Groceries.LIST_ID) as AlfredResult.Fresh).data.items.orEmpty().map { it.text!! }
    }

    private fun lineOf(name: String): String = runBlocking {
        groceries.getShoppingList(Groceries.LIST_ID).dataOrNull()!!.items!!.first { it.text!!.startsWith(name) }.line!!
    }

    // --- read ---

    @Test
    fun `the list reads as any list does, in KitchenSync's order, each item targeted by its id`() = runBlocking {
        val milk = kitchen.add("Milk", 2.0, "l", source = "auto")
        kitchen.add("Eggs", 12.0)
        kitchen.add("Bread")

        val result = groceries.getShoppingList(Groceries.LIST_ID) as AlfredResult.Fresh

        assertEquals(
            listOf(
                ShoppingItemDto("Milk — 2 l", Groceries.lineOf(milk.id!!), false),
                ShoppingItemDto("Eggs — 12", Groceries.lineOf(2), false),
                ShoppingItemDto("Bread", Groceries.lineOf(3), false)
            ),
            result.data.items
        )
        assertEquals(Groceries.LIST_ID, result.data.list?.id)
        assertEquals("Groceries", result.data.list?.title)
        assertEquals(AlfredReachability.State.REACHABLE, KitchenSyncReachability.state.value)
    }

    @Test
    fun `the summary counts every item as still to buy`() = runBlocking {
        kitchen.add("Milk")
        kitchen.add("Eggs", 6.0)

        val summary = (groceries.getSummary() as AlfredResult.Fresh).data

        assertEquals(2, summary.unticked)
        assertEquals(Groceries.LIST_ID, summary.id)
        // A family list: no owner, never shared, no Keep ticked.
        assertEquals(null, summary.owner)
        assertEquals(null, summary.shared)
        assertEquals(null, summary.keepTicked)
    }

    @Test
    fun `how much reads after the name, and one of something is just its name`() {
        fun text(quantity: Double?, unit: String?) = Groceries.text(GroceryItemDto(1, " Flour ", quantity, unit))

        assertEquals("Flour", text(1.0, null))
        assertEquals("Flour", text(null, null))
        assertEquals("Flour — 1.5 kg", text(1.5, "kg"))
        assertEquals("Flour — 1 bag", text(1.0, " bag "))
        assertEquals("Flour — 3", text(3.0, ""))
        assertEquals(null, Groceries.text(GroceryItemDto(1, "  ", 1.0, null)))
    }

    // --- add, tick, remove ---

    @Test
    fun `an add puts one of it on KitchenSync's list`() = runBlocking {
        kitchen.add("Milk")

        val result = groceries.addShoppingItem(Groceries.LIST_ID, "  Oat milk ", key = "unused")

        assertEquals(ShoppingWriteResult.Done, result)
        assertEquals(listOf("Milk", "Oat milk"), kitchen.items.map { it.itemName })
        assertEquals(1.0, kitchen.items.last().quantity!!, 0.0)
    }

    @Test
    fun `a tick asks nothing, KitchenSync takes it off the list and puts it away itself`() = runBlocking {
        kitchen.add("Milk")
        kitchen.add("Eggs")

        val result = groceries.tickShoppingItem(Groceries.LIST_ID, lineOf("Milk"))

        assertEquals(ShoppingWriteResult.Done, result)
        assertEquals(listOf("Eggs"), shown())
        assertEquals(listOf("Milk"), kitchen.purchased)
        assertTrue(kitchen.requests.any { it.matches(Regex("POST /api/shopping-list/items/\\d+/purchase")) })
    }

    @Test
    fun `a remove takes it off without buying it`() = runBlocking {
        kitchen.add("Milk")
        kitchen.add("Eggs")

        val result = groceries.dropShoppingItem(Groceries.LIST_ID, lineOf("Eggs"))

        assertEquals(ShoppingWriteResult.Done, result)
        assertEquals(listOf("Milk"), shown())
        assertTrue(kitchen.purchased.isEmpty())
    }

    @Test
    fun `a tick of an item already gone answers with the list as it is now`() = runBlocking {
        kitchen.add("Milk")
        val line = lineOf("Milk")
        kitchen.items.clear()
        kitchen.add("Bread")

        val result = groceries.tickShoppingItem(Groceries.LIST_ID, line)

        assertEquals(listOf("Bread"), (result as ShoppingWriteResult.StaleTarget).current.map { it.text })
        assertTrue(kitchen.purchased.isEmpty())
    }

    @Test
    fun `a line that isn't a KitchenSync item's is never sent`() = runBlocking {
        kitchen.add("Milk")
        kitchen.requests.clear()

        groceries.dropShoppingItem(Groceries.LIST_ID, "- [ ] Milk")

        assertTrue(kitchen.requests.none { it.startsWith("DELETE") })
        assertEquals(listOf("Milk"), kitchen.items.map { it.itemName })
    }

    // --- unreachable ---

    @Test
    fun `unreachable, the list is the saved copy, and the freshness line can say so`() = runBlocking {
        kitchen.add("Milk")
        groceries.getShoppingList(Groceries.LIST_ID)
        kitchen.down = true
        now = 9_000L

        val result = groceries.getShoppingList(Groceries.LIST_ID)

        val stale = result as AlfredResult.Stale
        assertEquals(listOf("Milk"), stale.data.items?.map { it.text })
        assertEquals(1_000L, stale.cachedAtMillis)
        assertEquals(AlfredReachability.State.UNREACHABLE, KitchenSyncReachability.state.value)
    }

    @Test
    fun `unreachable with nothing saved, Groceries is unavailable`() = runBlocking {
        kitchen.down = true

        assertEquals(AlfredResult.Unavailable, groceries.getShoppingList(Groceries.LIST_ID))
        assertEquals(AlfredResult.Unavailable, groceries.getSummary())
    }

    @Test
    fun `unreachable, a write isn't made, and Groceries' writes never join the queue`() = runBlocking {
        kitchen.add("Milk")
        val line = lineOf("Milk")
        kitchen.down = true

        assertEquals(ShoppingWriteResult.Unreachable, groceries.addShoppingItem(Groceries.LIST_ID, "Eggs", "k"))
        assertEquals(ShoppingWriteResult.Unreachable, groceries.tickShoppingItem(Groceries.LIST_ID, line))
        assertEquals(ShoppingWriteResult.Unreachable, groceries.dropShoppingItem(Groceries.LIST_ID, line))
        assertFalse(groceries.queuesWrites)
        kitchen.down = false
        assertEquals(listOf("Milk"), kitchen.items.map { it.itemName })
    }

    @Test
    fun `KitchenSync sits beside Alfred on port 8400 unless an address is saved`() {
        assertEquals("192.168.1.100:8400", KitchenSyncSettings.besideAlfred("192.168.1.100:8200"))
        assertEquals("10.0.0.5:8400", KitchenSyncSettings.besideAlfred("10.0.0.5:8200"))
    }
}
