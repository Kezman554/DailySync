package com.personalmorningalarm.data.remote

import com.personalmorningalarm.data.model.Groceries
import com.personalmorningalarm.data.model.ShoppingListDetailDto

/**
 * Where one shopping list's items come from and go to: the Shopping screens' one
 * way to read and change a list, whichever service keeps it. The vault's lists are
 * [AlfredRepository]; Groceries is KitchenSync's ([GroceriesRepository]). An item is
 * targeted by its `line`, which each source fills with its own key (a vault line, a
 * KitchenSync id).
 *
 * Only what every list does is here. What's particular to the vault's lists
 * (owners, sharing, Keep ticked, Copy into) stays with [AlfredRepository].
 */
interface ShoppingItemSource {
    /** The list now, or the last copy saved when the service can't be reached. */
    suspend fun getShoppingList(listId: String): AlfredResult<ShoppingListDetailDto>

    suspend fun addShoppingItem(listId: String, text: String, key: String): ShoppingWriteResult

    suspend fun tickShoppingItem(listId: String, line: String, key: String? = null): ShoppingWriteResult

    suspend fun dropShoppingItem(listId: String, line: String): ShoppingWriteResult

    /**
     * Whether a write that found the service unreachable joins the offline queue
     * ([ShoppingSync]). Only where a resend can't double it: the vault's writes are
     * keyed; KitchenSync's aren't, so Groceries' writes are made live or not at all.
     */
    val queuesWrites: Boolean get() = true

    companion object {
        /** The source for [listId]: Groceries is KitchenSync's, every other list the vault's. */
        fun forList(listId: String, vault: ShoppingItemSource, groceries: () -> ShoppingItemSource): ShoppingItemSource =
            if (Groceries.isGroceries(listId)) groceries() else vault
    }
}
