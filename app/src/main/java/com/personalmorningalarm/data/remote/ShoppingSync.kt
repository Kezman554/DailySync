package com.personalmorningalarm.data.remote

import android.content.Context
import android.util.Log
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.dao.PendingShoppingWriteDao
import com.personalmorningalarm.data.entity.PendingShoppingWrite
import com.personalmorningalarm.data.entity.ShoppingVerb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Store-and-forward for shopping-list writes. Mirrors [ChalkboardSync] exactly, with
 * one difference: the queue is shared across every list, so each entry carries its
 * own [PendingShoppingWrite.listId] and replay dispatches to that list's endpoint.
 * Create-list is never queued here — it's online-only, handled directly by
 * [AlfredRepository.createShoppingList]. Delete-list is online-only too, but it runs
 * through [deleteList] because it has to clear the queue.
 */
class ShoppingSync(
    private val alfred: AlfredRepository,
    private val dao: PendingShoppingWriteDao
) {

    /** Everything queued, across every list, failed entries included. */
    val queue: Flow<List<PendingShoppingWrite>> = dao.observeAll()

    /** Flushes that changed something, so an open list screen can reconcile. */
    private val _flushes = MutableSharedFlow<FlushOutcome>(extraBufferCapacity = 4)
    val flushes: SharedFlow<FlushOutcome> = _flushes.asSharedFlow()

    data class FlushOutcome(val delivered: Int, val conflicted: Int, val remaining: Int) {
        val changedAnything: Boolean get() = delivered > 0 || conflicted > 0
    }

    suspend fun enqueue(listId: String, verb: ShoppingVerb, text: String, line: String? = null) {
        dao.insert(
            PendingShoppingWrite(
                listId = listId,
                verb = verb.name,
                text = text,
                line = line,
                createdAt = System.currentTimeMillis()
            )
        )
        Log.d(TAG, "Shopping ${verb.name} queued for sync (list=$listId)")
    }

    /** Cheap guard for the app-foreground trigger: don't touch the network for an empty queue. */
    suspend fun hasPending(): Boolean = dao.countPending() > 0

    /** Clears [listId]'s failed entries once its conflict notice has been dismissed. */
    suspend fun dismissFailed(listId: String) = dao.deleteFailedForList(listId)

    /**
     * Deletes [listId] on Alfred and, once it's gone, everything queued for it.
     *
     * The clearing matters beyond tidiness: a replay into a deleted list comes back
     * as a failed write, which stops the flush and so holds up every other list. The
     * lock keeps a flush from replaying into the list between the two steps.
     */
    suspend fun deleteList(listId: String): ShoppingDeleteResult = mutex.withLock {
        val result = alfred.deleteShoppingList(listId)
        if (result is ShoppingDeleteResult.Deleted) dao.deleteForList(listId)
        result
    }

    /** Replays the whole queue, across every list, in order. Safe to call speculatively. */
    suspend fun flush(): FlushOutcome {
        val outcome = mutex.withLock { runFlush() }
        if (outcome.changedAnything) _flushes.emit(outcome)
        return outcome
    }

    private suspend fun runFlush(): FlushOutcome {
        val pending = dao.getPending()
        var delivered = 0
        var conflicted = 0
        for (entry in pending) {
            when (replay(entry)) {
                ShoppingWriteResult.Done -> {
                    dao.delete(entry)
                    delivered++
                }
                is ShoppingWriteResult.StaleTarget -> {
                    // The repository already cached the 404's list for this entry's
                    // list — the freshest state we have. This entry is spent; the
                    // rest (including other lists') still get their go.
                    dao.markFailed(entry.id)
                    conflicted++
                    Log.d(TAG, "Queued ${entry.verb} (list=${entry.listId}) hit a stale line — kept for the notice")
                }
                ShoppingWriteResult.Unreachable -> {
                    Log.d(TAG, "Alfred still unreachable — ${pending.size - delivered - conflicted} write(s) stay queued")
                    return FlushOutcome(delivered, conflicted, dao.countPending())
                }
            }
        }
        if (delivered > 0 || conflicted > 0) {
            Log.d(TAG, "Shopping queue flushed: $delivered delivered, $conflicted conflicted")
        }
        return FlushOutcome(delivered, conflicted, dao.countPending())
    }

    private suspend fun replay(entry: PendingShoppingWrite): ShoppingWriteResult {
        return when (ShoppingVerb.fromName(entry.verb)) {
            ShoppingVerb.ADD -> alfred.addShoppingItem(entry.listId, entry.text)
            ShoppingVerb.TICK -> alfred.tickShoppingItem(entry.listId, entry.line ?: return failEntry(entry))
            ShoppingVerb.DROP -> alfred.dropShoppingItem(entry.listId, entry.line ?: return failEntry(entry))
            // A verb this build doesn't know (downgrade after a newer build queued
            // it): failing it keeps the notice honest and the queue moving.
            null -> failEntry(entry)
        }
    }

    /** An entry that can never be replayed reads as a conflict, not a crash or a stall. */
    private fun failEntry(entry: PendingShoppingWrite): ShoppingWriteResult {
        Log.w(TAG, "Unreplayable queue entry (verb=${entry.verb}, list=${entry.listId}) — marking failed")
        return ShoppingWriteResult.StaleTarget(emptyList())
    }

    companion object {
        private const val TAG = "PMA"

        // One mutex per process, held by the one instance.
        private val mutex = Mutex()

        @Volatile
        private var INSTANCE: ShoppingSync? = null

        fun getInstance(context: Context): ShoppingSync =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ShoppingSync(
                    AlfredRepository(context.applicationContext),
                    AppDatabase.getInstance(context.applicationContext).pendingShoppingWriteDao()
                ).also { INSTANCE = it }
            }
    }
}
