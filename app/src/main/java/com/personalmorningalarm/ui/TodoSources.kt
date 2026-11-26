package com.personalmorningalarm.ui

import android.util.Log
import com.personalmorningalarm.data.entity.ChalkboardVerb
import com.personalmorningalarm.data.entity.PendingChalkboardWrite
import com.personalmorningalarm.data.model.ChalkboardList
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.PhoneLists
import com.personalmorningalarm.data.model.RollingTodo
import com.personalmorningalarm.data.model.RollingTodoItem
import com.personalmorningalarm.data.model.tickedLine
import com.personalmorningalarm.data.model.updateFirst
import com.personalmorningalarm.data.model.withoutFirst
import com.personalmorningalarm.data.phone.PhoneListStore
import com.personalmorningalarm.data.remote.AddKey
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.AlfredWriteResult
import com.personalmorningalarm.data.remote.ChalkboardSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One write outcome the user should hear about. */
enum class TodoEvent {
    /** A tick/drop hit a stale line; the list on screen was replaced with Alfred's. */
    LIST_REFRESHED,
    /** Alfred was unreachable, so the write was queued instead of lost. */
    SAVED_OFFLINE
}

/** One list as the To-do screen sees it, wherever it lives. */
data class SourceState(
    /** The hub's last answer, or null for a phone list and before the first read. */
    val chalkboard: AlfredResult<List<ChalkboardTaskDto>>? = null,
    /**
     * The list as it should show: the hub's copy with queued edits laid over it, or
     * the phone's rows. Null while nothing is known: not read yet, or a hub list
     * that was never loaded and has nothing queued.
     */
    val items: List<RollingTodoItem>? = null,
    /** Queued writes that will be sent (hub only). */
    val queued: List<PendingChalkboardWrite> = emptyList(),
    /** Queued writes that never landed, for the notice (hub only). */
    val failed: List<PendingChalkboardWrite> = emptyList(),
    val loading: Boolean = false,
    /** True for a hub list: it has a saved-copy age, a sync line and a refresh. */
    val onHub: Boolean = false
)

/**
 * One of the To-do screen's two lists. The screen calls these and never asks where
 * the list lives: [HubTodoSource] and [PhoneTodoSource] are the only two that know.
 */
interface TodoListSource {
    val state: StateFlow<SourceState>
    fun refresh(quiet: Boolean = false)
    fun add(text: String)
    fun tick(item: RollingTodoItem)
    fun drop(item: RollingTodoItem)
}

/**
 * A chalkboard list on the hub: Nick's rolling to-do or the family chalkboard.
 *
 * Writes work anywhere. While the list is [AlfredResult.Fresh] they go straight to
 * Alfred (optimistic update, refetch to reconcile — the queue is never involved).
 * Otherwise — offline, or a direct write that found Alfred just gone — they're
 * captured into [ChalkboardSync]'s persistent queue, tagged with [list], and the
 * screen renders snapshot + queued edits until a flush delivers them. Each refresh
 * flushes the queue first, so coming home reconciles on pull.
 */
class HubTodoSource(
    private val alfred: AlfredRepository,
    private val sync: ChalkboardSync,
    val list: ChalkboardList,
    private val scope: CoroutineScope,
    private val events: MutableSharedFlow<TodoEvent>
) : TodoListSource {

    private val _state = MutableStateFlow(SourceState(loading = true, onHub = true))
    override val state: StateFlow<SourceState> = _state.asStateFlow()

    private var inFlight: Job? = null

    init {
        scope.launch {
            sync.queue.collect { all ->
                val mine = all.filter { it.list == list.wireName }
                update { it.copy(queued = mine.filterNot(PendingChalkboardWrite::failed), failed = mine.filter(PendingChalkboardWrite::failed)) }
            }
        }
        scope.launch {
            // A flush can happen outside this screen (app foreground, network
            // change). Refetch so delivered entries reappear as real rows rather
            // than vanishing with their queue entries.
            sync.flushes.collect { reconcile() }
        }
    }

    /** Ignored while a refresh is already running, so repeated pulls don't stack. */
    override fun refresh(quiet: Boolean) {
        if (inFlight?.isActive == true) {
            Log.d(TAG, "To-do ${list.wireName}: refresh already in flight — ignored")
            return
        }
        inFlight = scope.launch {
            // A quiet refresh is FreshnessWatch retrying a saved copy: no spinner every minute.
            if (!quiet) update { it.copy(loading = true) }
            // Queued writes go first so the refetch below sees their effect. The
            // flush is shared by both lists and runs under one mutex, so the second
            // list's flush finds nothing left to send.
            if (sync.hasPending()) sync.flush()
            val result = alfred.getChalkboard(list)
            update { it.copy(loading = false, chalkboard = result) }
            Log.d(TAG, "To-do ${list.wireName}: refreshed — ${result.label()}")
        }
    }

    /** Appends [text]. Blank input is the caller's to reject; re-checked here. */
    override fun add(text: String) {
        // The vault is line-oriented — an embedded newline would split into two items.
        val task = text.replace(NEWLINES, " ").trim()
        if (task.isEmpty()) return
        // Made now and kept with the add: every attempt, live or queued, sends it.
        val key = AddKey.new()
        if (!isFresh()) {
            queueWrite(ChalkboardVerb.ADD, text = task, key = key)
            return
        }
        // No line yet — Alfred composes the real one; the refetch brings it back.
        applyOptimistically { it + ChalkboardTaskDto(task = task, date = null, line = null, ticked = false) }
        runWrite(
            "add",
            onUnreachable = { queueWrite(ChalkboardVerb.ADD, text = task, key = key) },
            onRefused = { sync.recordRefused(list, task, key) }
        ) {
            alfred.addChalkboardItem(list, task, key)
        }
    }

    /** Ticks the item — done, greyed out, swept overnight. Needs its vault line. */
    override fun tick(item: RollingTodoItem) {
        val line = item.line ?: return
        val text = displayTextFor(line)
        if (!isFresh()) {
            queueWrite(ChalkboardVerb.TICK, text = text, line = line)
            return
        }
        applyOptimistically { rows ->
            rows.updateFirst({ it.line == line }) { it.copy(line = tickedLine(line), ticked = true) }
        }
        // No reconcile on success: the flipped local copy is exactly what the vault
        // now holds, and the overnight sweep is what makes the item leave.
        runWrite(
            "tick",
            reconcileOnSuccess = false,
            onUnreachable = { queueWrite(ChalkboardVerb.TICK, text = text, line = line) }
        ) { alfred.tickChalkboardItem(list, line) }
    }

    /** Drops the item — no longer relevant, removed outright. Needs its vault line. */
    override fun drop(item: RollingTodoItem) {
        val line = item.line ?: return
        val text = displayTextFor(line)
        if (!isFresh()) {
            queueWrite(ChalkboardVerb.DROP, text = text, line = line)
            return
        }
        applyOptimistically { rows -> rows.withoutFirst { it.line == line } }
        runWrite("drop", onUnreachable = { queueWrite(ChalkboardVerb.DROP, text = text, line = line) }) {
            alfred.dropChalkboardItem(list, line)
        }
    }

    private fun isFresh(): Boolean = _state.value.chalkboard is AlfredResult.Fresh

    /**
     * Captures a write into the persistent queue. No state change here: the queue
     * flow updates [SourceState.queued], and the merged items are what show the
     * edit — one source of optimism, not two.
     */
    private fun queueWrite(verb: ChalkboardVerb, text: String, line: String? = null, key: String? = null) {
        scope.launch {
            sync.enqueue(list, verb, text, line, key)
            events.emit(TodoEvent.SAVED_OFFLINE)
        }
    }

    /** The item's display text, for the conflict notice should this write never land. */
    private fun displayTextFor(line: String): String {
        val rows = (_state.value.chalkboard as? AlfredResult.Fresh)?.data
            ?: (_state.value.chalkboard as? AlfredResult.Stale)?.data
        return rows?.firstOrNull { it.line == line }?.task ?: line
    }

    private fun applyOptimistically(mutate: (List<ChalkboardTaskDto>) -> List<ChalkboardTaskDto>) {
        update { state ->
            val fresh = state.chalkboard as? AlfredResult.Fresh ?: return@update state
            state.copy(chalkboard = fresh.copy(data = mutate(fresh.data)))
        }
    }

    private fun runWrite(
        verb: String,
        reconcileOnSuccess: Boolean = true,
        onUnreachable: suspend () -> Unit,
        onRefused: suspend () -> Unit = {},
        write: suspend () -> AlfredWriteResult
    ) {
        scope.launch {
            when (val result = write()) {
                AlfredWriteResult.Done -> if (reconcileOnSuccess) reconcile()
                AlfredWriteResult.Refused -> {
                    // A bug, not Alfred: kept as a failed change for the notice, never resent.
                    Log.w(TAG, "To-do ${list.wireName}: $verb refused — shown as failed")
                    onRefused()
                    reconcile()
                }
                is AlfredWriteResult.StaleTarget -> {
                    // The 404 carries the open items only. Read the list again for
                    // its greyed ticked rows; if that fails, the 404's list will do.
                    val reread = alfred.getChalkboard(list)
                    val current = reread as? AlfredResult.Fresh
                        ?: AlfredResult.Fresh(result.current, System.currentTimeMillis())
                    update { it.copy(chalkboard = current) }
                    events.emit(TodoEvent.LIST_REFRESHED)
                }
                AlfredWriteResult.Unreachable -> {
                    // Alfred vanished between the fetch and this write. The change
                    // is captured, not lost: into the queue, and the base list is
                    // re-read (the cache holds the pre-write server truth) so the
                    // merged rendering is the single source of the optimism.
                    Log.d(TAG, "To-do ${list.wireName}: $verb didn't land — queueing")
                    onUnreachable()
                    reconcile()
                }
            }
        }
    }

    /** Refetches the list alone, without the spinner: an in-place edit, not a refresh. */
    private suspend fun reconcile() {
        // Fetch first, then read-and-copy the state in one step, so a queue update
        // landing during the fetch isn't rolled back by a stale snapshot.
        val result = alfred.getChalkboard(list)
        update { it.copy(chalkboard = result) }
    }

    /** Every change goes through here, so [SourceState.items] always matches the rest. */
    private inline fun update(change: (SourceState) -> SourceState) {
        while (true) {
            val before = _state.value
            val after = change(before).let { it.copy(items = itemsOf(it)) }
            if (_state.compareAndSet(before, after)) return
        }
    }

    private fun itemsOf(state: SourceState): List<RollingTodoItem>? {
        val base = when (val result = state.chalkboard) {
            is AlfredResult.Fresh -> result.data
            is AlfredResult.Stale -> result.data
            AlfredResult.Unavailable, null -> null
        }
        // No copy AND nothing queued: only then is there truly nothing to list.
        if (base == null && state.queued.isEmpty()) return null
        return RollingTodo.merged(base.orEmpty(), state.queued, list)
    }

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

/**
 * The to-do kept on the phone. Only [PhoneListStore] is here, so nothing this list
 * does can reach Alfred or the offline queue. Ticked items stay greyed until the
 * store's daily clear at 03:07, the hub's sweep time.
 */
class PhoneTodoSource(
    private val store: PhoneListStore,
    scope: CoroutineScope
) : TodoListSource {

    override val state: StateFlow<SourceState> = store.todoItems()
        .map { SourceState(items = PhoneLists.todoItems(it)) }
        .stateIn(scope, SharingStarted.Eagerly, SourceState())

    init {
        store.sweep()
    }

    /** Nothing to fetch; the daily clear's tidying is all a refresh can do. */
    override fun refresh(quiet: Boolean) {
        store.sweep()
    }

    override fun add(text: String) {
        store.addTodo(text)
    }

    override fun tick(item: RollingTodoItem) {
        item.localId?.let(store::tick)
    }

    override fun drop(item: RollingTodoItem) {
        item.localId?.let(store::drop)
    }
}
