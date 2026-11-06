package com.personalmorningalarm.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.model.ShoppingList
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
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
 * Backs the shopping list-picker menu: discovery (read-only, cache-backed like every
 * other Alfred screen) plus creating and deleting lists. Both are online-only: a name
 * collision is the API's call, not something the queue can pre-empt, and a delete
 * shouldn't wait in a queue behind edits to the list it removes. [sync] is here only
 * so a delete can clear what was queued for that list.
 */
class ShoppingMenuViewModel(
    private val alfred: AlfredRepository,
    private val sync: ShoppingSync
) : ViewModel() {

    data class MenuState(
        val loading: Boolean = false,
        val lists: AlfredResult<List<ShoppingListSummaryDto>>? = null,
        /** A delete is with Alfred. One at a time, and the spinner shows the wait. */
        val deleting: Boolean = false
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

    init {
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

    /** Blank or whitespace-only names are the caller's to reject; re-checked here. */
    fun createList(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
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

    /** Deletes [list]. Ignored while another delete is with Alfred. */
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
                    _state.value = MenuState(lists = AlfredResult.Fresh(result.remaining, System.currentTimeMillis()))
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

/** Builds [ShoppingMenuViewModel] with the (repository, sync) shape [AlfredViewModelFactory] doesn't cover. */
class ShoppingMenuViewModelFactory(
    private val repository: AlfredRepository,
    private val sync: ShoppingSync
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ShoppingMenuViewModel(repository, sync) as T
}
