package com.personalmorningalarm.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.ShoppingCreateResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the shopping list-picker menu: discovery (read-only, cache-backed like every
 * other Alfred screen) plus creating a new list. Create is online-only — a name
 * collision is the API's call, not something the queue can pre-empt — so it takes no
 * [com.personalmorningalarm.data.remote.ShoppingSync]; the plain single-arg
 * [AlfredViewModelFactory] shape covers this ViewModel.
 */
class ShoppingMenuViewModel(private val alfred: AlfredRepository) : ViewModel() {

    data class MenuState(
        val loading: Boolean = false,
        val lists: AlfredResult<List<ShoppingListSummaryDto>>? = null
    )

    /** One-shot outcomes of a create attempt the user should hear about. */
    sealed interface CreateEvent {
        data class Created(val title: String) : CreateEvent
        data object Conflict : CreateEvent
        data object Unreachable : CreateEvent
    }

    private val _state = MutableStateFlow(MenuState(loading = true))
    val state: StateFlow<MenuState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<CreateEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CreateEvent> = _events.asSharedFlow()

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
            _state.value = MenuState(loading = false, lists = result)
        }
    }

    /** Blank or whitespace-only names are the caller's to reject; re-checked here. */
    fun createList(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            when (val result = alfred.createShoppingList(trimmed)) {
                is ShoppingCreateResult.Created -> {
                    _events.emit(CreateEvent.Created(result.list.title ?: trimmed))
                    refresh()
                }
                ShoppingCreateResult.Conflict -> _events.emit(CreateEvent.Conflict)
                ShoppingCreateResult.Unreachable -> {
                    Log.d(TAG, "Shopping: create list failed — Alfred unreachable")
                    _events.emit(CreateEvent.Unreachable)
                }
            }
        }
    }

    private companion object {
        const val TAG = "PMA"
    }
}
