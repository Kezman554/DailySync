package com.personalmorningalarm.ui

import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.model.Groceries
import com.personalmorningalarm.data.model.ShoppingItemDto
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResponseCache
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.AlfredSettings
import com.personalmorningalarm.data.remote.FakeKitchenSync
import com.personalmorningalarm.data.remote.GroceriesRepository
import com.personalmorningalarm.data.remote.KitchenSyncClient
import com.personalmorningalarm.data.remote.KitchenSyncReachability
import com.personalmorningalarm.data.remote.ShoppingSync
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Groceries on the shopping list screen: the same ViewModel every list uses, its
 * source KitchenSync rather than Alfred. Its writes go live, and with KitchenSync
 * unreachable they're refused out loud, never queued.
 */
@RunWith(RobolectricTestRunner::class)
class GroceriesListViewModelTest : ViewModelTestSupport() {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val kitchen = FakeKitchenSync()

    // Alfred is pointed at nothing: Groceries must never need it.
    private val alfred by lazy {
        AlfredSettings(context).setHost("127.0.0.1:9")
        AlfredRepository(AlfredSettings(context), AlfredResponseCache(context))
    }
    private val sync by lazy { ShoppingSync(alfred, db.pendingShoppingWriteDao()) }
    private val groceries by lazy {
        GroceriesRepository(AlfredResponseCache(context), { KitchenSyncClient.service(kitchen.baseUrl) })
    }

    @Before
    fun setUp() {
        kitchen.start()
        AlfredResponseCache(context).clear(GroceriesRepository.CACHE_KEY)
    }

    @After
    fun tearDown() {
        kitchen.shutdown()
        AlfredReachability.reset()
        KitchenSyncReachability.reset()
    }

    private fun loaded(): ShoppingListViewModel {
        val vm = track(ShoppingListViewModel(alfred, sync, Groceries.LIST_ID) { groceries })
        keepSubscribed(vm.state)
        awaitValue(vm.state) { !it.loading && it.result is AlfredResult.Fresh }
        return vm
    }

    private fun names(vm: ShoppingListViewModel): List<String?> =
        (vm.state.value.result as AlfredResult.Fresh).data.map(ShoppingItemDto::text)

    @Test
    fun `Groceries opens on KitchenSync's list, with no owner or sharing to show`() {
        kitchen.add("Milk")
        kitchen.add("Eggs", 6.0)

        val vm = loaded()

        assertEquals(listOf("Milk", "Eggs — 6"), names(vm))
        assertEquals(null, vm.state.value.summary)
        assertEquals(false, vm.state.value.keepTicked)
    }

    @Test
    fun `add, tick and remove work against KitchenSync, a tick taking the item off`() {
        kitchen.add("Milk")
        kitchen.add("Eggs")
        val vm = loaded()

        vm.addItem("Bread")
        awaitValue(vm.state) { s -> (s.result as? AlfredResult.Fresh)?.data?.any { it.text == "Bread" && it.line != null } == true }
        val milk = (vm.state.value.result as AlfredResult.Fresh).data.first { it.text == "Milk" }.line!!
        vm.tickItem(milk)
        awaitValue(vm.state) { s -> (s.result as? AlfredResult.Fresh)?.data?.none { it.text == "Milk" } == true }
        val eggs = (vm.state.value.result as AlfredResult.Fresh).data.first { it.text == "Eggs" }.line!!
        vm.dropItem(eggs)
        awaitValue(vm.state) { s -> (s.result as? AlfredResult.Fresh)?.data?.map { it.text } == listOf("Bread") }
        // The screen shows it gone at once; KitchenSync has it gone once the call lands.
        awaitValue(vm.state) { kitchen.items.map { it.itemName } == listOf("Bread") }

        assertEquals(listOf("Bread"), kitchen.items.map { it.itemName })
        assertEquals(listOf("Milk"), kitchen.purchased)
    }

    @Test
    fun `with KitchenSync unreachable a change isn't made or queued, and the screen says so`() = runBlocking {
        kitchen.add("Milk")
        val vm = loaded()
        val milk = (vm.state.value.result as AlfredResult.Fresh).data.single().line!!
        kitchen.down = true
        val events = java.util.Collections.synchronizedList(mutableListOf<ShoppingListViewModel.ShoppingEvent>())
        val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            .launch { vm.events.collect { events += it } }

        vm.addItem("Bread")
        awaitValue(vm.state) { events.size >= 1 }
        vm.tickItem(milk)
        awaitValue(vm.state) { events.size >= 2 }
        collector.cancel()

        assertEquals(List(2) { ShoppingListViewModel.ShoppingEvent.SOURCE_UNREACHABLE }, events.toList())
        assertTrue(db.pendingShoppingWriteDao().getPending().isEmpty())
        kitchen.down = false
        assertEquals(listOf("Milk"), kitchen.items.map { it.itemName })
        assertTrue(kitchen.purchased.isEmpty())
    }
}
