package com.personalmorningalarm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.model.PhoneLists
import com.personalmorningalarm.data.model.ShoppingItem
import com.personalmorningalarm.data.phone.PhoneListStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One shopping list kept on the phone: add, tick, drop. Only [PhoneListStore] is
 * here, so nothing this screen does can reach Alfred or the offline queue. Writes
 * run in the store's own scope and land even if the screen is left at once.
 */
class PhoneShoppingListViewModel(
    private val store: PhoneListStore,
    private val listId: String
) : ViewModel() {

    /** The list as it stands; null until the first read. */
    val items: StateFlow<List<ShoppingItem>?> = store.items(listId)
        .map(PhoneLists::shoppingItems)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        store.sweep()
    }

    fun addItem(text: String) {
        store.add(listId, text)
    }

    fun tickItem(item: ShoppingItem) {
        item.localId?.let(store::tick)
    }

    fun dropItem(item: ShoppingItem) {
        item.localId?.let(store::drop)
    }
}
