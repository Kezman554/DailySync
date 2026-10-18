package com.personalmorningalarm.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.entity.ChalkboardVerb
import com.personalmorningalarm.data.entity.PendingChalkboardWrite
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.ScheduleTaskDto
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.AlfredWriteResult
import com.personalmorningalarm.data.remote.ChalkboardSync
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the Today screen: both Alfred feeds, refreshed on demand, and the
 * chalkboard's three writes — add, tick, drop.
 *
 * Writes work anywhere. While the chalkboard is [AlfredResult.Fresh] they go
 * straight to Alfred (optimistic update, refetch to reconcile — the queue is
 * never involved). Otherwise — offline, or a direct write that found Alfred
 * just gone — they're captured into [ChalkboardSync]'s persistent queue, and
 * the screen renders snapshot + queued edits until a flush delivers them.
 * Each refresh flushes the queue first, so coming home reconciles on pull.
 */
class TodayViewModel(
    private val alfred: AlfredRepository,
    private val sync: ChalkboardSync
) : ViewModel() {

    /**
     * Each feed is held separately and rendered separately: one endpoint being
     * unreachable must not blank the other. Null means "not fetched yet", which the
     * screen shows as loading rather than as a failure. [pending] is the offline
     * write queue, failed entries included — the screen derives its markers,
     * queued-count line and conflict notice from it.
     */
    data class TodayState(
        val loading: Boolean = false,
        val schedule: AlfredResult<List<ScheduleTaskDto>>? = null,
        val chalkboard: AlfredResult<List<ChalkboardTaskDto>>? = null,
        val pending: List<PendingChalkboardWrite> = emptyList()
    )

    /** One-shot outcomes of a write that the user should hear about. */
    enum class TodayEvent {
        /** A tick/drop hit a stale line; the list on screen was replaced with Alfred's. */
        LIST_REFRESHED,
        /** Alfred was unreachable, so the write was queued instead of lost. */
        SAVED_OFFLINE
    }

    private val _state = MutableStateFlow(TodayState(loading = true))
    val state: StateFlow<TodayState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TodayEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<TodayEvent> = _events.asSharedFlow()

    private var inFlight: Job? = null

    init {
        viewModelScope.launch {
            sync.queue.collect { _state.value = _state.value.copy(pending = it) }
        }
        viewModelScope.launch {
            // A flush can happen outside this screen (app foreground, network
            // change). Refetch so delivered entries reappear as real rows rather
            // than vanishing with their queue entries.
            sync.flushes.collect { reconcileChalkboard() }
        }
        refresh()
    }

    /** Ignored while a refresh is already running, so repeated pulls don't stack. */
    fun refresh(quiet: Boolean = false) {
        if (inFlight?.isActive == true) {
            Log.d(TAG, "Today: refresh already in flight — ignored")
            return
        }
        inFlight = viewModelScope.launch {
            // A quiet refresh is FreshnessWatch retrying a saved copy: no spinner every minute.
            if (!quiet) _state.value = _state.value.copy(loading = true)
            // Queued writes go first so the refetch below sees their effect —
            // otherwise the pull that greets home WiFi would show a list the
            // queue is about to change. A no-op when nothing is queued.
            if (sync.hasPending()) sync.flush()
            coroutineScope {
                // Both feeds at once — measured on device, they do start together
                // (1ms apart, different threads). Note that offline this still takes
                // ~6s rather than ~3s: OkHttp doesn't give each attempt on the same
                // unreachable host its own timeout window, so the second waits out the
                // first. Tolerable here — the user asked for this screen and the
                // spinner shows the wait — and it can't touch the alarm, where each
                // content screen makes a single call.
                val schedule = async { alfred.getDailySchedule() }
                val chalkboard = async { alfred.getChalkboard() }
                // Neither call throws — Alfred being unreachable comes back as a
                // result, so there's no partial-failure state to unpick here.
                val scheduleResult = schedule.await()
                val chalkboardResult = chalkboard.await()
                _state.value = _state.value.copy(
                    loading = false,
                    schedule = scheduleResult,
                    chalkboard = chalkboardResult
                )
                Log.d(
                    TAG,
                    "Today: refreshed — schedule=${scheduleResult.label()}, " +
                        "chalkboard=${chalkboardResult.label()}"
                )
            }
        }
    }

    /** The user has read the conflict notice; the failed entries are done with. */
    fun dismissFailed() {
        viewModelScope.launch { sync.dismissFailed() }
    }

    /** Appends [text] to the rolling to-do. Blank input is the caller's to reject; re-checked here. */
    fun addItem(text: String) {
        // The vault is line-oriented — an embedded newline would split into two items.
        val task = text.replace(NEWLINES, " ").trim()
        if (task.isEmpty()) return
        if (!chalkboardIsFresh()) {
            queueWrite(ChalkboardVerb.ADD, text = task)
            return
        }
        // No line yet — Alfred composes the real one; the refetch brings it back.
        applyOptimistically { it + ChalkboardTaskDto(task = task, date = null, line = null) }
        runWrite("add", onUnreachable = { queueWrite(ChalkboardVerb.ADD, text = task) }) {
            alfred.addChalkboardItem(task)
        }
    }

    /** Ticks the item targeted by [line] — done, greyed out, swept overnight. */
    fun tickItem(line: String) {
        val text = displayTextFor(line)
        if (!chalkboardIsFresh()) {
            queueWrite(ChalkboardVerb.TICK, text = text, line = line)
            return
        }
        applyOptimistically { list ->
            list.map { if (it.line == line) it.copy(line = tickedLine(line)) else it }
        }
        // No reconcile on success: Alfred's listings exclude ticked items straight
        // away, so a refetch would hide the greyed row the user should still see.
        // The flipped local copy is exactly what the vault now holds; the next
        // refresh (or the overnight sweep) is what makes the item leave.
        runWrite(
            "tick",
            reconcileOnSuccess = false,
            onUnreachable = { queueWrite(ChalkboardVerb.TICK, text = text, line = line) }
        ) { alfred.tickChalkboardItem(line) }
    }

    /** Drops the item targeted by [line] — no longer relevant, removed outright. */
    fun dropItem(line: String) {
        val text = displayTextFor(line)
        if (!chalkboardIsFresh()) {
            queueWrite(ChalkboardVerb.DROP, text = text, line = line)
            return
        }
        applyOptimistically { list -> list.filterNot { it.line == line } }
        runWrite("drop", onUnreachable = { queueWrite(ChalkboardVerb.DROP, text = text, line = line) }) {
            alfred.dropChalkboardItem(line)
        }
    }

    private fun chalkboardIsFresh(): Boolean = _state.value.chalkboard is AlfredResult.Fresh

    /**
     * Captures a write into the persistent queue. No state mutation here: the
     * queue flow updates [TodayState.pending], and the screen's merged rendering
     * is what shows the edit — one source of optimism, not two.
     */
    private fun queueWrite(verb: ChalkboardVerb, text: String, line: String? = null) {
        viewModelScope.launch {
            sync.enqueue(verb, text, line)
            _events.emit(TodayEvent.SAVED_OFFLINE)
        }
    }

    /** The item's display text, for the conflict notice should this write never land. */
    private fun displayTextFor(line: String): String {
        val list = (_state.value.chalkboard as? AlfredResult.Fresh)?.data
            ?: (_state.value.chalkboard as? AlfredResult.Stale)?.data
        return list?.firstOrNull { it.line == line }?.task ?: line
    }

    /**
     * Shows the write's effect immediately, while the chalkboard is Fresh — the
     * only state writes are offered in. The follow-up refetch is what makes the
     * optimism honest either way.
     */
    private fun applyOptimistically(mutate: (List<ChalkboardTaskDto>) -> List<ChalkboardTaskDto>) {
        val fresh = _state.value.chalkboard as? AlfredResult.Fresh ?: return
        _state.value = _state.value.copy(chalkboard = fresh.copy(data = mutate(fresh.data)))
    }

    private fun runWrite(
        verb: String,
        reconcileOnSuccess: Boolean = true,
        onUnreachable: suspend () -> Unit,
        write: suspend () -> AlfredWriteResult
    ) {
        viewModelScope.launch {
            when (val result = write()) {
                AlfredWriteResult.Done -> if (reconcileOnSuccess) reconcileChalkboard()
                is AlfredWriteResult.StaleTarget -> {
                    // The current list came back in the same round trip — use it.
                    _state.value = _state.value.copy(chalkboard = AlfredResult.Fresh(result.current, System.currentTimeMillis()))
                    _events.emit(TodayEvent.LIST_REFRESHED)
                }
                AlfredWriteResult.Unreachable -> {
                    // Alfred vanished between the fetch and this write. The change
                    // is captured, not lost: into the queue, and the base list is
                    // re-read (the cache holds the pre-write server truth) so the
                    // merged rendering is the single source of the optimism.
                    Log.d(TAG, "Today: chalkboard $verb didn't land — queueing")
                    onUnreachable()
                    reconcileChalkboard()
                }
            }
        }
    }

    /**
     * Refetches the chalkboard alone — the schedule hasn't changed, and a full
     * refresh() would flash the spinner over what should feel like an in-place edit.
     */
    private suspend fun reconcileChalkboard() {
        // Fetch first, then read-and-copy the state in one step: evaluating
        // _state.value before the suspend point would capture a snapshot that a
        // concurrent queue update (pending markers) could land inside, and the
        // copy would silently roll it back.
        val result = alfred.getChalkboard()
        _state.value = _state.value.copy(chalkboard = result)
    }

    /** "[ ]" → "[x]" for the optimistic tick; the refetch supplies Alfred's actual line. */
    private fun tickedLine(line: String): String = line.replaceFirst("[ ]", "[x]")

    /** Fresh/Stale/Unavailable, for the log — the whole point is they differ per feed. */
    private fun AlfredResult<List<*>>.label(): String = when (this) {
        is AlfredResult.Fresh -> "fresh(${data.size})"
        is AlfredResult.Stale -> "stale(${data.size})"
        AlfredResult.Unavailable -> "unavailable"
    }

    private companion object {
        const val TAG = "PMA"
        val NEWLINES = Regex("""\R+""")
    }
}
