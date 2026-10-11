package com.personalmorningalarm.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.model.PresenceStatusDto
import com.personalmorningalarm.data.remote.PresenceRepository
import com.personalmorningalarm.data.remote.PresenceResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the kitchen sign-in tile: read whether the wall's presence session is
 * open, open it with a PIN, close it.
 *
 * Unlike the other Alfred-backed tiles this takes no sync/queue — on purpose.
 * Every action is one live call through [PresenceRepository]; an unreachable
 * dashboard is reported and forgotten, never retried later. The PIN arrives as an
 * argument to [signIn] and goes straight into the request: it is not held in
 * state, so it can't outlive the attempt or land in a saved-state bundle.
 *
 * The session may be opened at the wall, extended by touches there, or lapse on
 * its own, so the status is re-read whenever the screen starts and again when the
 * local countdown says it should have expired.
 */
class PresenceViewModel(
    private val presence: PresenceRepository,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : ViewModel() {

    sealed interface Session {
        /** Not read yet, or the dashboard didn't answer — we don't know. */
        data object Unknown : Session
        data object Closed : Session
        /** Open for [person] until [expiresAt] on the [clock] — null if the dashboard gave no expiry. */
        data class Open(val person: String?, val expiresAt: Long?) : Session
    }

    data class PresenceState(
        val loading: Boolean = false,
        /** An open or close is in flight — the buttons disable so it can't be sent twice. */
        val busy: Boolean = false,
        val session: Session = Session.Unknown,
        /** The last status read failed to reach the dashboard. */
        val unreachable: Boolean = false
    )

    sealed interface PresenceEvent {
        data object SignedIn : PresenceEvent
        data object WrongPin : PresenceEvent
        data class LockedOut(val retryAfterSeconds: Int?) : PresenceEvent
        data object NotConfigured : PresenceEvent
        data object SignedOut : PresenceEvent
        /** The dashboard didn't answer. Nothing was queued — the attempt is simply over. */
        data object Unreachable : PresenceEvent
        data class Failed(val httpCode: Int) : PresenceEvent
    }

    private val _state = MutableStateFlow(PresenceState())
    val state: StateFlow<PresenceState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PresenceEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PresenceEvent> = _events.asSharedFlow()

    private var reading: Job? = null
    private var expiryCheck: Job? = null

    /** Re-reads the session. Called whenever the screen starts — it may have changed elsewhere. */
    fun refresh() {
        if (reading?.isActive == true) return
        reading = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val result = presence.status()
            if (result is PresenceResult.Status) {
                show(result.status)
            } else {
                // Unreachable or unexpected: we don't know, and say so rather than
                // keep showing a session state that may no longer be true.
                expiryCheck?.cancel()
                _state.value = _state.value.copy(session = Session.Unknown, unreachable = true)
            }
            _state.value = _state.value.copy(loading = false)
        }
    }

    /** One attempt to open the session with [pin]. Blank input sends nothing. */
    fun signIn(pin: String) {
        if (pin.isBlank() || _state.value.busy) return
        act {
            when (val result = presence.open(pin)) {
                is PresenceResult.Status -> {
                    show(result.status)
                    _events.emit(
                        if (result.status.open) PresenceEvent.SignedIn else PresenceEvent.Failed(200)
                    )
                }
                PresenceResult.WrongPin -> _events.emit(PresenceEvent.WrongPin)
                is PresenceResult.LockedOut -> _events.emit(PresenceEvent.LockedOut(result.retryAfterSeconds))
                PresenceResult.NotConfigured -> _events.emit(PresenceEvent.NotConfigured)
                is PresenceResult.Failed -> _events.emit(PresenceEvent.Failed(result.httpCode))
                PresenceResult.Unreachable -> {
                    _state.value = _state.value.copy(unreachable = true)
                    _events.emit(PresenceEvent.Unreachable)
                }
            }
        }
    }

    fun signOut() {
        if (_state.value.busy) return
        act {
            when (val result = presence.close()) {
                is PresenceResult.Status -> {
                    show(result.status)
                    _events.emit(PresenceEvent.SignedOut)
                }
                PresenceResult.Unreachable -> {
                    // The session may still be open on the wall. Don't claim otherwise.
                    _state.value = _state.value.copy(unreachable = true)
                    _events.emit(PresenceEvent.Unreachable)
                }
                is PresenceResult.Failed -> _events.emit(PresenceEvent.Failed(result.httpCode))
                // Not answers /close gives, but a refusal is still a refusal.
                PresenceResult.WrongPin, PresenceResult.NotConfigured, is PresenceResult.LockedOut ->
                    _events.emit(PresenceEvent.Failed(0))
            }
        }
    }

    private fun act(block: suspend () -> Unit) {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                block()
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    private fun show(status: PresenceStatusDto) {
        expiryCheck?.cancel()
        val session = if (status.open) {
            val expiresInMs = status.expiresInSeconds?.let { (it * 1000).toLong() }
            Session.Open(status.person, expiresInMs?.let { clock() + it })
        } else {
            Session.Closed
        }
        _state.value = _state.value.copy(session = session, unreachable = false)

        // When the session should have lapsed, ask again: a touch at the wall
        // may have extended it, and only the dashboard knows.
        if (session is Session.Open && session.expiresAt != null) {
            expiryCheck = viewModelScope.launch {
                delay((session.expiresAt - clock()).coerceAtLeast(0) + EXPIRY_GRACE_MS)
                refresh()
            }
        }
    }

    private companion object {
        /** A beat past the expiry, so the re-read lands after the dashboard has lapsed it. */
        const val EXPIRY_GRACE_MS = 1_000L
    }
}

/** Builds [PresenceViewModel] — it takes a [PresenceRepository], not the Alfred shapes. */
class PresenceViewModelFactory(
    private val repository: PresenceRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PresenceViewModel(repository) as T
}
