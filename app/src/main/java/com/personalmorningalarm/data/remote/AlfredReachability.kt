package com.personalmorningalarm.data.remote

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's one answer to "can we see Alfred right now": how the last call to him
 * went, whichever screen or queue made it.
 *
 * Not a second detector. Only [AlfredRepository] reports here, from the same
 * outcome the offline write queues act on — the call that comes back
 * [AlfredWriteResult.Unreachable] (and its shopping/inbox twins) is the one that
 * marks Alfred unreachable, and a failed read counts the same way; any answer
 * marks him reachable, a 404 or a refusal included. So a queue flush on
 * reconnect and a tile refresh move the same switch, and nothing polls it.
 */
object AlfredReachability {

    enum class State {
        /** Nothing has asked yet this process. */
        UNKNOWN,
        REACHABLE,
        UNREACHABLE
    }

    private val _state = MutableStateFlow(State.UNKNOWN)
    val state: StateFlow<State> = _state.asStateFlow()

    internal fun report(reachable: Boolean) {
        _state.value = if (reachable) State.REACHABLE else State.UNREACHABLE
    }

    @VisibleForTesting
    fun reset() {
        _state.value = State.UNKNOWN
    }
}
