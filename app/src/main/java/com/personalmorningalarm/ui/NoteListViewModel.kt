package com.personalmorningalarm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.entity.PendingNoteWrite
import com.personalmorningalarm.data.model.NoteSummaryDto
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.NoteSync
import com.personalmorningalarm.data.remote.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the notes list: Alfred's list, with the queue merged on top by the screen
 * ([com.personalmorningalarm.data.model.Notes.merged]). The one write here is a
 * delete; saves come from the editor.
 */
class NoteListViewModel(
    private val alfred: AlfredRepository,
    private val sync: NoteSync
) : ViewModel() {

    data class NoteListState(
        val loading: Boolean = false,
        val result: AlfredResult<List<NoteSummaryDto>>? = null,
        val pending: List<PendingNoteWrite> = emptyList()
    )

    private val _state = MutableStateFlow(NoteListState(loading = true))
    val state: StateFlow<NoteListState> = _state.asStateFlow()

    private var inFlight: Job? = null

    init {
        viewModelScope.launch {
            sync.queue.collect { pending ->
                // A row leaves the queue when it's delivered, and the saved list has
                // already taken the news by then. Reading it now means a delivered
                // note doesn't vanish, or a deleted one reappear, until the refetch.
                _state.value = _state.value.let { it.copy(pending = pending, result = withSavedNotes(it.result)) }
            }
        }
        viewModelScope.launch {
            // A flush can happen outside this screen (app foreground, network change).
            sync.flushes.collect { if (it.changedAnything) reconcile() }
        }
        refresh()
    }

    private fun withSavedNotes(
        result: AlfredResult<List<NoteSummaryDto>>?
    ): AlfredResult<List<NoteSummaryDto>>? {
        val saved = alfred.savedNotes() ?: return result
        return result?.map { saved }
    }

    /** Ignored while a refresh is already running, so repeated pulls don't stack. */
    fun refresh(quiet: Boolean = false) {
        if (inFlight?.isActive == true) return
        inFlight = viewModelScope.launch {
            // A quiet refresh is FreshnessWatch retrying a saved copy: no spinner every minute.
            if (!quiet) _state.value = _state.value.copy(loading = true)
            // Queued notes go first, so the fetch below sees them as real notes.
            if (sync.hasPending()) sync.flush()
            // Fetch first, then read-and-copy the state: see InboxViewModel.refresh.
            val result = alfred.getNotes()
            _state.value = _state.value.copy(loading = false, result = result)
        }
    }

    fun delete(noteId: String) {
        sync.delete(noteId)
    }

    private suspend fun reconcile() {
        // Fetch first, then copy — see refresh() for why the order matters.
        val result = alfred.getNotes()
        _state.value = _state.value.copy(result = result)
    }
}

/** Builds [NoteListViewModel] with the (repository, sync) shape [AlfredViewModelFactory] doesn't cover. */
class NoteListViewModelFactory(
    private val repository: AlfredRepository,
    private val sync: NoteSync
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        NoteListViewModel(repository, sync) as T
}
