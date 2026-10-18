package com.personalmorningalarm.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.entity.PendingShoppingWrite
import com.personalmorningalarm.data.entity.ShoppingVerb
import com.personalmorningalarm.data.model.ShoppingItemDto
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.ShoppingSync
import com.personalmorningalarm.data.remote.ShoppingWriteResult
import com.personalmorningalarm.data.remote.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs one shopping list's screen: add/tick/drop, behaving exactly like the rolling
 * to-do's editing in [TodayViewModel] (this is that ViewModel's chalkboard half,
 * scoped to one list instead of the one chalkboard, and reading/writing through
 * [ShoppingSync] instead of [com.personalmorningalarm.data.remote.ChalkboardSync]).
 *
 * Writes work anywhere: live they go straight to Alfred (optimistic update, refetch
 * to reconcile); offline — or a direct write that finds Alfred just gone — they're
 * captured into the shared [ShoppingSync] queue, tagged with [listId].
 */
class ShoppingListViewModel(
    private val alfred: AlfredRepository,
    private val sync: ShoppingSync,
    private val listId: String
) : ViewModel() {

    data class ShoppingListState(
        val loading: Boolean = false,
        val result: AlfredResult<List<ShoppingItemDto>>? = null,
        val pending: List<PendingShoppingWrite> = emptyList()
    )

    enum class ShoppingEvent {
        /** A tick/drop hit a stale line; the list on screen was replaced with Alfred's. */
        LIST_REFRESHED,
        /** Alfred was unreachable, so the write was queued instead of lost. */
        SAVED_OFFLINE
    }

    private val _state = MutableStateFlow(ShoppingListState(loading = true))
    val state: StateFlow<ShoppingListState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ShoppingEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ShoppingEvent> = _events.asSharedFlow()

    private var inFlight: Job? = null

    init {
        viewModelScope.launch {
            sync.queue.collect { all ->
                _state.value = _state.value.copy(pending = all.filter { it.listId == listId })
            }
        }
        viewModelScope.launch {
            // A flush can happen outside this screen (app foreground, network
            // change). Refetch so delivered entries reappear as real rows rather
            // than vanishing with their queue entries — only when it touched this list.
            sync.flushes.collect { if (it.changedAnything) reconcile() }
        }
        refresh()
    }

    fun refresh(quiet: Boolean = false) {
        if (inFlight?.isActive == true) return
        inFlight = viewModelScope.launch {
            // A quiet refresh is FreshnessWatch retrying a saved copy: no spinner every minute.
            if (!quiet) _state.value = _state.value.copy(loading = true)
            // Queued writes for every list go first, same as the rolling to-do's
            // refresh, so the refetch below sees their effect.
            if (sync.hasPending()) sync.flush()
            val result = alfred.getShoppingList(listId)
            _state.value = _state.value.copy(loading = false, result = result.map { it.items.orEmpty() })
        }
    }

    fun dismissFailed() {
        viewModelScope.launch { sync.dismissFailed(listId) }
    }

    fun addItem(text: String) {
        val task = text.replace(NEWLINES, " ").trim()
        if (task.isEmpty()) return
        if (!isFresh()) {
            queueWrite(ShoppingVerb.ADD, text = task)
            return
        }
        applyOptimistically { it + ShoppingItemDto(text = task, line = null, ticked = false) }
        runWrite("add", onUnreachable = { queueWrite(ShoppingVerb.ADD, text = task) }) {
            alfred.addShoppingItem(listId, task)
        }
    }

    fun tickItem(line: String) {
        val text = displayTextFor(line)
        if (!isFresh()) {
            queueWrite(ShoppingVerb.TICK, text = text, line = line)
            return
        }
        applyOptimistically { list -> list.map { if (it.line == line) it.copy(ticked = true) else it } }
        runWrite(
            "tick",
            reconcileOnSuccess = true,
            onUnreachable = { queueWrite(ShoppingVerb.TICK, text = text, line = line) }
        ) { alfred.tickShoppingItem(listId, line) }
    }

    fun dropItem(line: String) {
        val text = displayTextFor(line)
        if (!isFresh()) {
            queueWrite(ShoppingVerb.DROP, text = text, line = line)
            return
        }
        applyOptimistically { list -> list.filterNot { it.line == line } }
        runWrite("drop", onUnreachable = { queueWrite(ShoppingVerb.DROP, text = text, line = line) }) {
            alfred.dropShoppingItem(listId, line)
        }
    }

    private fun isFresh(): Boolean = _state.value.result is AlfredResult.Fresh

    private fun queueWrite(verb: ShoppingVerb, text: String, line: String? = null) {
        viewModelScope.launch {
            sync.enqueue(listId, verb, text, line)
            _events.emit(ShoppingEvent.SAVED_OFFLINE)
        }
    }

    private fun displayTextFor(line: String): String {
        val list = (_state.value.result as? AlfredResult.Fresh)?.data
            ?: (_state.value.result as? AlfredResult.Stale)?.data
        return list?.firstOrNull { it.line == line }?.text ?: line
    }

    private fun applyOptimistically(mutate: (List<ShoppingItemDto>) -> List<ShoppingItemDto>) {
        val fresh = _state.value.result as? AlfredResult.Fresh ?: return
        _state.value = _state.value.copy(result = fresh.copy(data = mutate(fresh.data)))
    }

    private fun runWrite(
        verb: String,
        reconcileOnSuccess: Boolean = true,
        onUnreachable: suspend () -> Unit,
        write: suspend () -> ShoppingWriteResult
    ) {
        viewModelScope.launch {
            when (val result = write()) {
                ShoppingWriteResult.Done -> if (reconcileOnSuccess) reconcile()
                is ShoppingWriteResult.StaleTarget -> {
                    _state.value = _state.value.copy(result = AlfredResult.Fresh(result.current, System.currentTimeMillis()))
                    _events.emit(ShoppingEvent.LIST_REFRESHED)
                }
                ShoppingWriteResult.Unreachable -> {
                    Log.d(TAG, "Shopping $verb didn't land (list=$listId) — queueing")
                    onUnreachable()
                    reconcile()
                }
            }
        }
    }

    private suspend fun reconcile() {
        val result = alfred.getShoppingList(listId)
        _state.value = _state.value.copy(result = result.map { it.items.orEmpty() })
    }

    private companion object {
        const val TAG = "PMA"
        val NEWLINES = Regex("""\R+""")
    }
}

/** Builds [ShoppingListViewModel] with the (repository, sync, listId) shape [AlfredViewModelFactory] doesn't cover. */
class ShoppingListViewModelFactory(
    private val repository: AlfredRepository,
    private val sync: ShoppingSync,
    private val listId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ShoppingListViewModel(repository, sync, listId) as T
}
