package com.personalmorningalarm.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.model.ShoppingList
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.phone.ListHome
import com.personalmorningalarm.data.phone.PhoneListStore
import com.personalmorningalarm.data.phone.PhoneListSummary
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.ShoppingCreateResult
import com.personalmorningalarm.data.remote.ShoppingDeleteResult
import com.personalmorningalarm.data.remote.ShoppingSync
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs the shopping list-picker menu: the hub's lists from discovery (read-only,
 * cache-backed like every other Alfred screen), the phone's own lists beside them,
 * and creating and deleting either.
 *
 * On the hub both are online-only: a name collision is the API's call, not something
 * the queue can pre-empt, and a delete shouldn't wait in a queue behind edits to the
 * list it removes. [sync] is here only so a hub delete can clear what was queued for
 * that list. A phone list is made and deleted in [phone], offline or not, and never
 * reaches Alfred or the queue.
 */
class ShoppingMenuViewModel(
    private val alfred: AlfredRepository,
    private val sync: ShoppingSync,
    private val phone: PhoneListStore
) : ViewModel() {

    data class MenuState(
        val loading: Boolean = false,
        val lists: AlfredResult<List<ShoppingListSummaryDto>>? = null,
        /** A delete is with Alfred. One at a time, and the spinner shows the wait. */
        val deleting: Boolean = false,
        /** The phone's own lists, oldest first. Null until the first read. */
        val phoneLists: List<PhoneListSummary>? = null
    )

    /** One-shot outcomes of a create or delete attempt the user should hear about. */
    sealed interface MenuEvent {
        data class Created(val title: String) : MenuEvent
        data object Conflict : MenuEvent
        data object Unreachable : MenuEvent
        data class Deleted(val title: String) : MenuEvent
        data object DeleteUnreachable : MenuEvent
    }

    private val _state = MutableStateFlow(MenuState(loading = true))
    val state: StateFlow<MenuState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<MenuEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MenuEvent> = _events.asSharedFlow()

    private var inFlight: Job? = null

    /** Where the open create field will put the new list. Chosen as the field opens. */
    var createHome: ListHome = ListHome.HUB

    init {
        phone.sweep()
        viewModelScope.launch {
            phone.shoppingLists().collect { _state.value = _state.value.copy(phoneLists = it) }
        }
        refresh()
    }

    /** Ignored while a refresh is already running, so repeated pulls don't stack. */
    fun refresh(quiet: Boolean = false) {
        if (inFlight?.isActive == true) return
        inFlight = viewModelScope.launch {
            // A quiet refresh is FreshnessWatch retrying a saved copy: no spinner every minute.
            if (!quiet) _state.value = _state.value.copy(loading = true)
            val result = alfred.getShoppingLists()
            _state.value = _state.value.copy(loading = false, lists = result)
        }
    }

    /**
     * Makes a list called [name] wherever [createHome] says. Blank or whitespace-only
     * names are the caller's to reject; re-checked here.
     */
    fun createList(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (createHome == ListHome.PHONE) {
            createPhoneList(trimmed)
            return
        }
        viewModelScope.launch {
            when (val result = alfred.createShoppingList(trimmed)) {
                is ShoppingCreateResult.Created -> {
                    _events.emit(MenuEvent.Created(result.list.title ?: trimmed))
                    refresh()
                }
                ShoppingCreateResult.Conflict -> _events.emit(MenuEvent.Conflict)
                ShoppingCreateResult.Unreachable -> {
                    Log.d(TAG, "Shopping: create list failed — Alfred unreachable")
                    _events.emit(MenuEvent.Unreachable)
                }
            }
        }
    }

    private fun createPhoneList(name: String) {
        viewModelScope.launch {
            when (val result = phone.createShoppingList(name)) {
                is PhoneListStore.CreateResult.Created -> _events.emit(MenuEvent.Created(result.list.title))
                PhoneListStore.CreateResult.Conflict -> _events.emit(MenuEvent.Conflict)
                PhoneListStore.CreateResult.Blank -> Unit
            }
        }
    }

    /** Deletes a phone list and everything on it. Nothing to wait for and nobody to ask. */
    fun deletePhoneList(list: ShoppingListSummaryDto) {
        val id = list.id ?: return
        phone.deleteList(id)
        _events.tryEmit(MenuEvent.Deleted(ShoppingList.shortTitle(list.title ?: id)))
    }

    /** Deletes [list] from the hub. Ignored while another delete is with Alfred. */
    fun deleteList(list: ShoppingListSummaryDto) {
        val id = list.id ?: return
        if (_state.value.deleting) return
        _state.value = _state.value.copy(deleting = true)
        viewModelScope.launch {
            // Leaving the screen mustn't cancel this: the Pi would finish the delete
            // regardless, and the queue would keep writes for a list that's gone.
            val result = withContext(NonCancellable) { sync.deleteList(id) }
            when (result) {
                is ShoppingDeleteResult.Deleted -> {
                    // A refresh begun before the delete would bring the list back.
                    inFlight?.cancel()
                    _state.value = _state.value.copy(
                        loading = false,
                        deleting = false,
                        lists = AlfredResult.Fresh(result.remaining, System.currentTimeMillis())
                    )
                    _events.emit(MenuEvent.Deleted(ShoppingList.shortTitle(list.title ?: id)))
                }
                ShoppingDeleteResult.Unreachable -> {
                    Log.d(TAG, "Shopping: delete list failed — Alfred unreachable")
                    _state.value = _state.value.copy(deleting = false)
                    _events.emit(MenuEvent.DeleteUnreachable)
                }
            }
        }
    }

    private companion object {
        const val TAG = "PMA"
    }
}

/** Builds [ShoppingMenuViewModel] with the (repository, sync, phone) shape [AlfredViewModelFactory] doesn't cover. */
class ShoppingMenuViewModelFactory(
    private val repository: AlfredRepository,
    private val sync: ShoppingSync,
    private val phone: PhoneListStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ShoppingMenuViewModel(repository, sync, phone) as T
}
