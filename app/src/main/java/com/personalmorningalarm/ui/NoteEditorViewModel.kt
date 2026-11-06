package com.personalmorningalarm.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.entity.NoteVerb
import com.personalmorningalarm.data.model.NoteDto
import com.personalmorningalarm.data.model.Notes
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.NoteSync
import com.personalmorningalarm.data.remote.dataOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One note in the editor. Nothing is written while typing: the screen hands over the
 * text as it's left, and this decides what that means ([Notes.leaving]).
 *
 * The editor only opens on the note's real text, from the queue or from Alfred. The
 * list's preview is a short extract, so an editor that opened on anything less would
 * overwrite the note, and one that opened empty would offer to delete it. If the text
 * can't be had, nothing here can write at all.
 *
 * The screen surviving is not assumed. After a process death the note is reloaded
 * from wherever the last leave put it, and [handle] remembers that a new note has
 * been kept, so it comes back as the same note and not a blank new one.
 */
class NoteEditorViewModel(
    private val alfred: AlfredRepository,
    private val sync: NoteSync,
    val noteId: String,
    isNew: Boolean,
    private val handle: SavedStateHandle
) : ViewModel() {

    sealed interface EditorState {
        data object Loading : EditorState

        /** [source] is Alfred's answer, or null for text that came from the phone. */
        data class Ready(val source: AlfredResult<NoteDto>? = null) : EditorState

        /** Not in the queue and not reachable: nothing to edit, so nothing to write. */
        data object Unavailable : EditorState
    }

    private val _state = MutableStateFlow<EditorState>(EditorState.Loading)
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /** The text to show: the note as opened, then as last left. Outlives a rotation. */
    var draft: String = ""
        private set

    /** What the vault or the queue holds. Null until there's something to lose. */
    var kept: String? = null
        private set

    private var deleted = false

    init {
        if (isNew && handle.get<Boolean>(KEY_KEPT) != true) {
            _state.value = EditorState.Ready()
        } else {
            viewModelScope.launch { load() }
        }
    }

    private suspend fun load() {
        val queued = sync.queued(noteId)
        when {
            queued == null || queued.failed && queued.verb == NoteVerb.DELETE.name -> loadFromAlfred()
            queued.verb == NoteVerb.SAVE.name -> open(queued.text, source = null)
            else -> _state.value = EditorState.Unavailable
        }
    }

    private suspend fun loadFromAlfred() {
        val result = alfred.getNote(noteId)
        val text = result.dataOrNull()?.text
        if (text == null) {
            _state.value = EditorState.Unavailable
        } else {
            open(text, result)
        }
    }

    private fun open(text: String, source: AlfredResult<NoteDto>?) {
        draft = text
        kept = text
        _state.value = EditorState.Ready(source)
    }

    /**
     * The user is going back to the list. On [Notes.Leaving.CONFIRM_DELETE] they stay
     * until they've answered; anything else has already been taken care of.
     */
    fun leave(text: String): Notes.Leaving {
        if (deleted || _state.value !is EditorState.Ready) return Notes.Leaving.NOTHING
        draft = text
        val leaving = Notes.leaving(text, kept)
        if (leaving == Notes.Leaving.SAVE) {
            kept = text
            handle[KEY_KEPT] = true
            sync.save(noteId, text)
        }
        return leaving
    }

    /**
     * The app is going to the background. Nobody is there to confirm a delete, so an
     * emptied note just stays as it is, and the question waits for them to leave.
     */
    fun background(text: String) {
        leave(text)
    }

    /** Answers [Notes.Leaving.CONFIRM_DELETE] with yes. */
    fun confirmDelete() {
        deleted = true
        sync.delete(noteId)
    }

    /** Answers [Notes.Leaving.CONFIRM_DELETE] with no, and returns the text to put back. */
    fun keepNote(): String {
        draft = kept.orEmpty()
        return draft
    }

    private companion object {
        const val KEY_KEPT = "kept"
    }
}
