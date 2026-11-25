package com.personalmorningalarm.data.remote

import android.content.Context
import android.util.Log
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.dao.PendingChalkboardWriteDao
import com.personalmorningalarm.data.entity.ChalkboardVerb
import com.personalmorningalarm.data.entity.PendingChalkboardWrite
import com.personalmorningalarm.data.model.ChalkboardList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Store-and-forward for chalkboard writes, both lists in one FIFO queue: anything captured while Alfred is
 * unreachable waits in a Room-backed FIFO queue and is replayed when he's back.
 * This is capture, not live sync — nothing here polls; [flush] runs only on the
 * event-driven triggers (app foreground, network change, manual refresh).
 *
 * One instance app-wide ([getInstance]): the flush mutex is what stops the
 * foreground trigger and a network-change trigger replaying the same entry twice.
 *
 * Replay outcomes, per entry, in queue order:
 *  - delivered → entry removed;
 *  - stale target (404) → entry marked failed and kept for the screen's notice,
 *    never retried, and the rest of the queue continues past it;
 *  - unreachable → the flush stops, everything left stays queued for next time.
 */
class ChalkboardSync(
    private val alfred: AlfredRepository,
    private val dao: PendingChalkboardWriteDao
) {

    /** Everything queued, failed entries included — the screen's markers and notices. */
    val queue: Flow<List<PendingChalkboardWrite>> = dao.observeAll()

    /**
     * Flushes that changed something, wherever they were triggered from. The Today
     * screen listens so a background flush (app foreground, network change)
     * reconciles an open screen too — without this, delivered entries leave the
     * queue while the list still shows the pre-flush snapshot, and the user
     * watches their items vanish until a manual refresh.
     */
    private val _flushes = MutableSharedFlow<FlushOutcome>(extraBufferCapacity = 4)
    val flushes: SharedFlow<FlushOutcome> = _flushes.asSharedFlow()

    /** What a [flush] did, so the caller knows whether a reconciling refetch is worth it. */
    data class FlushOutcome(val delivered: Int, val conflicted: Int, val remaining: Int) {
        val changedAnything: Boolean get() = delivered > 0 || conflicted > 0
    }

    suspend fun enqueue(list: ChalkboardList, verb: ChalkboardVerb, text: String, line: String? = null) {
        dao.insert(
            PendingChalkboardWrite(
                verb = verb.name,
                text = text,
                line = line,
                createdAt = System.currentTimeMillis(),
                list = list.wireName
            )
        )
        Log.d(TAG, "Chalkboard ${list.wireName} ${verb.name} queued for sync")
    }

    /** Cheap guard for the app-foreground trigger: don't touch the network for an empty queue. */
    suspend fun hasPending(): Boolean = dao.countPending() > 0

    /** Clears the failed entries once their conflict notice has been dismissed. */
    suspend fun dismissFailed() = dao.deleteFailed()

    /** Replays the queue in order. Safe to call speculatively; a no-op when empty. */
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
                AlfredWriteResult.Done -> {
                    dao.delete(entry)
                    delivered++
                }
                is AlfredWriteResult.StaleTarget -> {
                    // This entry is spent; the rest still get their go. The flush
                    // event makes an open screen refetch the list it hit.
                    dao.markFailed(entry.id)
                    conflicted++
                    Log.d(TAG, "Queued ${entry.verb} hit a stale line — kept for the notice")
                }
                AlfredWriteResult.Unreachable -> {
                    Log.d(TAG, "Alfred still unreachable — ${pending.size - delivered - conflicted} write(s) stay queued")
                    return FlushOutcome(delivered, conflicted, dao.countPending())
                }
            }
        }
        if (delivered > 0 || conflicted > 0) {
            Log.d(TAG, "Chalkboard queue flushed: $delivered delivered, $conflicted conflicted")
        }
        return FlushOutcome(delivered, conflicted, dao.countPending())
    }

    private suspend fun replay(entry: PendingChalkboardWrite): AlfredWriteResult {
        // A list this build doesn't know is never guessed at: it could reach the wall.
        val list = ChalkboardList.fromWireName(entry.list) ?: return failEntry(entry)
        return when (ChalkboardVerb.fromName(entry.verb)) {
            ChalkboardVerb.ADD -> alfred.addChalkboardItem(list, entry.text)
            ChalkboardVerb.TICK -> alfred.tickChalkboardItem(list, entry.line ?: return failEntry(entry))
            ChalkboardVerb.DROP -> alfred.dropChalkboardItem(list, entry.line ?: return failEntry(entry))
            // A verb this build doesn't know (downgrade after a newer build queued
            // it): failing it keeps the notice honest and the queue moving.
            null -> failEntry(entry)
        }
    }

    /** An entry that can never be replayed reads as a conflict, not a crash or a stall. */
    private fun failEntry(entry: PendingChalkboardWrite): AlfredWriteResult {
        Log.w(TAG, "Unreplayable queue entry (verb=${entry.verb}) — marking failed")
        return AlfredWriteResult.StaleTarget(emptyList())
    }

    companion object {
        private const val TAG = "PMA"

        // One mutex per process, held by the one instance.
        private val mutex = Mutex()

        @Volatile
        private var INSTANCE: ChalkboardSync? = null

        fun getInstance(context: Context): ChalkboardSync =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChalkboardSync(
                    AlfredRepository(context.applicationContext),
                    AppDatabase.getInstance(context.applicationContext).pendingChalkboardWriteDao()
                ).also { INSTANCE = it }
            }
    }
}
