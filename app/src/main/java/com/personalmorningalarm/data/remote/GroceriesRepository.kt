package com.personalmorningalarm.data.remote

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.personalmorningalarm.data.model.Groceries
import com.personalmorningalarm.data.model.GroceryItemDto
import com.personalmorningalarm.data.model.ShoppingListDetailDto
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import retrofit2.Response

/**
 * Groceries: KitchenSync's grocery list as a [ShoppingItemSource], the Shopping
 * screens' way to any list.
 *
 * Reads behave as Alfred's do ([AlfredRepository.fetch]): each answer is saved, and
 * with KitchenSync unreachable the saved copy is served ([AlfredResult.Stale]), or
 * [AlfredResult.Unavailable] if there is none. [KitchenSyncReachability] says
 * whether it last answered, for the freshness line, as [AlfredReachability] does
 * for Alfred.
 *
 * Writes are live or not at all: they never join the offline queue
 * ([queuesWrites]). KitchenSync takes no Idempotency-Key, and every add is a new
 * row, so an add replayed after a lost answer would be a second one; a tick moves
 * stock. A write that gets no answer is [ShoppingWriteResult.Unreachable] and the
 * screen says so; a 404 (the item already gone, ticked or removed elsewhere) is
 * [ShoppingWriteResult.StaleTarget] with the list as it is now.
 */
class GroceriesRepository(
    private val cache: AlfredResponseCache,
    private val service: () -> KitchenSyncApiService,
    private val clock: () -> Long = System::currentTimeMillis
) : ShoppingItemSource {

    constructor(context: Context) : this(
        AlfredResponseCache(context),
        KitchenSyncSettings(context).let { settings -> { KitchenSyncClient.service(settings.baseUrl()) } }
    )

    private val gson = Gson()

    override val queuesWrites: Boolean get() = false

    /** The list as the Shopping menu and Home tile count it. */
    suspend fun getSummary(): AlfredResult<ShoppingListSummaryDto> = read().map(Groceries::summary)

    override suspend fun getShoppingList(listId: String): AlfredResult<ShoppingListDetailDto> =
        read().map(Groceries::detail)

    /** One item called [text], one of it. [key] is unused: KitchenSync takes none. */
    override suspend fun addShoppingItem(listId: String, text: String, key: String): ShoppingWriteResult =
        write("add") { it.addGrocery(GroceryAddRequest(itemName = text.trim())) }

    /** Bought: it leaves the list, into stock or the "to put away" tray, as KitchenSync decides. */
    override suspend fun tickShoppingItem(listId: String, line: String, key: String?): ShoppingWriteResult {
        val id = Groceries.idOf(line) ?: return stale("tick")
        return write("tick") { it.purchaseGrocery(id) }
    }

    /** No longer wanted: it leaves the list, nothing stocked. */
    override suspend fun dropShoppingItem(listId: String, line: String): ShoppingWriteResult {
        val id = Groceries.idOf(line) ?: return stale("drop")
        return write("drop") { it.deleteGrocery(id) }
    }

    private suspend fun read(): AlfredResult<List<GroceryItemDto>> = withContext(Dispatchers.IO) {
        try {
            val items = service().getGroceries()
            val at = clock()
            cache.put(CACHE_KEY, gson.toJson(items), at)
            KitchenSyncReachability.report(true)
            AlfredResult.Fresh(items, at)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.d(TAG, "KitchenSync unreachable for groceries (${e.javaClass.simpleName}) — trying the saved copy")
            KitchenSyncReachability.report(false)
            saved()
        }
    }

    private fun saved(): AlfredResult<List<GroceryItemDto>> {
        val cached = cache.get(CACHE_KEY) ?: return AlfredResult.Unavailable
        return try {
            val items: List<GroceryItemDto>? = gson.fromJson(cached.json, ITEMS_TYPE)
            items?.let { AlfredResult.Stale(it, cached.cachedAtMillis) } ?: AlfredResult.Unavailable
        } catch (e: Exception) {
            Log.w(TAG, "Saved groceries unreadable — discarding", e)
            cache.clear(CACHE_KEY)
            AlfredResult.Unavailable
        }
    }

    private suspend fun write(verb: String, call: suspend (KitchenSyncApiService) -> Response<*>): ShoppingWriteResult =
        withContext(Dispatchers.IO) {
            try {
                val response = call(service())
                KitchenSyncReachability.report(true)
                when {
                    response.isSuccessful -> ShoppingWriteResult.Done
                    response.code() == 404 -> stale(verb)
                    else -> {
                        Log.w(TAG, "Groceries $verb refused: HTTP ${response.code()}")
                        ShoppingWriteResult.Unreachable
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                Log.d(TAG, "KitchenSync unreachable for groceries $verb (${e.javaClass.simpleName})")
                KitchenSyncReachability.report(false)
                ShoppingWriteResult.Unreachable
            }
        }

    /** The item isn't on the list any more: here's the list as it is, to show instead. */
    private suspend fun stale(verb: String): ShoppingWriteResult {
        Log.d(TAG, "Groceries $verb: the item had already gone")
        val now = read() as? AlfredResult.Fresh ?: return ShoppingWriteResult.Unreachable
        return ShoppingWriteResult.StaleTarget(Groceries.items(now.data))
    }

    companion object {
        private const val TAG = "PMA"

        /** Saved beside Alfred's answers, under a key no vault endpoint uses. */
        const val CACHE_KEY = "kitchensync/shopping-list"

        private val ITEMS_TYPE = object : TypeToken<List<GroceryItemDto>>() {}.type
    }
}

/** Whether KitchenSync last answered: Groceries' freshness line, as [AlfredReachability] is Alfred's. */
object KitchenSyncReachability {
    private val _state = MutableStateFlow(AlfredReachability.State.UNKNOWN)
    val state: StateFlow<AlfredReachability.State> = _state.asStateFlow()

    internal fun report(reachable: Boolean) {
        _state.value = if (reachable) AlfredReachability.State.REACHABLE else AlfredReachability.State.UNREACHABLE
    }

    @VisibleForTesting
    fun reset() {
        _state.value = AlfredReachability.State.UNKNOWN
    }
}
