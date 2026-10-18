package com.personalmorningalarm.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.entity.PendingInboxWrite
import com.personalmorningalarm.data.model.InboxCaptureDto
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.InboxSync
import com.personalmorningalarm.data.remote.InboxWriteResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the inbox tile: read the vault's current captures, write new ones.
 *
 * Deliberately half of what [ShoppingListViewModel] does. The list is read-only —
 * inbox items are resolved in a vault triage session, never from the phone — so
 * there's no tick, no drop, and no stale-target reconciliation. The one write is
 * capture, which takes the same offline path as everything else: live it goes
 * straight to Alfred, otherwise it's queued through [InboxSync].
 */
class InboxViewModel(
    private val alfred: AlfredRepository,
    private val sync: InboxSync
) : ViewModel() {

    data class InboxState(
        val loading: Boolean = false,
        val result: AlfredResult<List<InboxCaptureDto>>? = null,
        val pending: List<PendingInboxWrite> = emptyList()
    )

    enum class InboxEvent {
        /** The capture went straight to the vault. */
        CAPTURED,
        /** Alfred was unreachable, so the capture was queued instead of lost. */
        SAVED_OFFLINE,
        /** Alfred answered and refused the capture — it won't be retried. */
        REFUSED
    }

    private val _state = MutableStateFlow(InboxState(loading = true))
    val state: StateFlow<InboxState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<InboxEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<InboxEvent> = _events.asSharedFlow()

    private var inFlight: Job? = null

    init {
        viewModelScope.launch {
            sync.queue.collect { _state.value = _state.value.copy(pending = it) }
        }
        viewModelScope.launch {
            // A flush can happen outside this screen (app foreground, network
            // change). Refetch so delivered captures reappear as real vault rows
            // rather than vanishing with their queue entries.
            sync.flushes.collect { if (it.changedAnything) reconcile() }
        }
        refresh()
    }

    fun refresh(quiet: Boolean = false) {
        if (inFlight?.isActive == true) return
        inFlight = viewModelScope.launch {
            // A quiet refresh is FreshnessWatch retrying a saved copy: no spinner every minute.
            if (!quiet) _state.value = _state.value.copy(loading = true)
            // Queued captures go first, same as the other tiles' refresh, so the
            // refetch below sees them as real files.
            if (sync.hasPending()) sync.flush()
            // Fetch first, then read-and-copy the state: evaluating _state.value
            // before the suspend point captures a snapshot that a concurrent queue
            // update (a capture refused during the flush above) lands inside, and
            // the copy would silently roll it back — the refused notice vanished.
            val result = alfred.getInbox()
            _state.value = _state.value.copy(loading = false, result = result)
        }
    }

    fun dismissFailed() {
        viewModelScope.launch { sync.dismissFailed() }
    }

    /**
     * Captures [text] as a new inbox file. Newlines are kept — unlike a to-do item
     * or a shopping line, a capture is free-form prose and may well be several lines.
     */
    fun capture(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        if (!isFresh()) {
            queueCapture(body)
            return
        }
        viewModelScope.launch {
            when (alfred.captureToInbox(body)) {
                InboxWriteResult.Done -> {
                    _events.emit(InboxEvent.CAPTURED)
                    reconcile()
                }
                InboxWriteResult.Rejected -> {
                    Log.w(TAG, "Alfred refused the capture — not queueing it")
                    _events.emit(InboxEvent.REFUSED)
                }
                InboxWriteResult.Unreachable -> {
                    Log.d(TAG, "Capture didn't land — queueing")
                    queueCapture(body)
                    reconcile()
                }
            }
        }
    }

    private fun isFresh(): Boolean = _state.value.result is AlfredResult.Fresh

    private fun queueCapture(text: String) {
        viewModelScope.launch {
            sync.enqueue(text)
            _events.emit(InboxEvent.SAVED_OFFLINE)
        }
    }

    private suspend fun reconcile() {
        // Fetch first, then copy — see refresh() for why the order matters.
        val result = alfred.getInbox()
        _state.value = _state.value.copy(result = result)
    }

    private companion object {
        const val TAG = "PMA"
    }
}

/** Builds [InboxViewModel] with the (repository, sync) shape [AlfredViewModelFactory] doesn't cover. */
class InboxViewModelFactory(
    private val repository: AlfredRepository,
    private val sync: InboxSync
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        InboxViewModel(repository, sync) as T
}
