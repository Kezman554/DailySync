package com.personalmorningalarm.data.remote

import android.content.Context
import android.util.Log
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.dao.PendingNoteWriteDao
import com.personalmorningalarm.data.entity.NoteVerb
import com.personalmorningalarm.data.entity.PendingNoteWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Store-and-forward for notes. Mirrors [ShoppingSync], with two differences.
 *
 * Every write comes here first, online or not, then a flush delivers it: a note is
 * saved as its screen goes away, so the write has to be on disk before anything
 * else happens. That's also why [save] and [delete] run in [scope], which outlives
 * the screen: a ViewModel's scope is cancelled by the very Back press that saves.
 *
 * The queue keeps only the last write per note. Both of Alfred's writes are safe to
 * repeat, so replaying after a lost answer does no harm.
 */
class NoteSync(
    private val alfred: AlfredRepository,
    private val dao: PendingNoteWriteDao,
    private val scope: CoroutineScope = defaultScope()
) {

    /** Everything queued, refused entries included. */
    val queue: Flow<List<PendingNoteWrite>> = dao.observeAll()

    /** Flushes that changed something, so an open list can reconcile. */
    private val _flushes = MutableSharedFlow<FlushOutcome>(extraBufferCapacity = 4)
    val flushes: SharedFlow<FlushOutcome> = _flushes.asSharedFlow()

    data class FlushOutcome(val delivered: Int, val refused: Int, val remaining: Int) {
        val changedAnything: Boolean get() = delivered > 0 || refused > 0
    }

    /** Queues [text] exactly as given, replacing anything queued for [noteId], then tries to send it. */
    fun save(noteId: String, text: String): Job = write(noteId, NoteVerb.SAVE, text)

    /** Queues the delete, replacing any queued save, then tries to send it. */
    fun delete(noteId: String): Job = write(noteId, NoteVerb.DELETE, "")

    private fun write(noteId: String, verb: NoteVerb, text: String): Job = scope.launch {
        try {
            dao.put(
                PendingNoteWrite(
                    noteId = noteId,
                    verb = verb.name,
                    text = text,
                    createdAt = System.currentTimeMillis()
                )
            )
            Log.d(TAG, "Note ${verb.name} queued for sync")
            flush()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.e(TAG, "Note ${verb.name} couldn't be queued", e)
        }
    }

    /** What's queued for [noteId], if anything: newer than anything Alfred can answer. */
    suspend fun queued(noteId: String): PendingNoteWrite? = dao.find(noteId)

    /** Cheap guard for the app-foreground trigger: don't touch the network for an empty queue. */
    suspend fun hasPending(): Boolean = dao.countPending() > 0

    /** Replays the whole queue in order. Safe to call speculatively. */
    suspend fun flush(): FlushOutcome {
        val outcome = mutex.withLock { runFlush() }
        if (outcome.changedAnything) _flushes.emit(outcome)
        return outcome
    }

    /**
     * Reads the queue one entry at a time rather than taking a snapshot: each write
     * can take seconds, and a note edited or deleted meanwhile must send its new
     * state, not bring back the old one.
     */
    private suspend fun runFlush(): FlushOutcome {
        var delivered = 0
        var refused = 0
        var lastId = 0L
        while (true) {
            val entry = dao.nextPending(lastId) ?: break
            lastId = entry.id
            when (replay(entry)) {
                NoteWriteResult.Done -> {
                    dao.delete(entry)
                    delivered++
                }
                NoteWriteResult.Rejected -> {
                    // Retrying would stall every note behind it. The row keeps the
                    // text and the list says so; the rest still get their go.
                    dao.markFailed(entry.id)
                    refused++
                    Log.w(TAG, "Queued note ${entry.verb} refused by Alfred — kept on the phone")
                }
                NoteWriteResult.Unreachable -> {
                    Log.d(TAG, "Alfred still unreachable — notes stay queued")
                    return FlushOutcome(delivered, refused, dao.countPending())
                }
            }
        }
        if (delivered > 0 || refused > 0) {
            Log.d(TAG, "Note queue flushed: $delivered delivered, $refused refused")
        }
        return FlushOutcome(delivered, refused, dao.countPending())
    }

    private suspend fun replay(entry: PendingNoteWrite): NoteWriteResult =
        when (NoteVerb.fromName(entry.verb)) {
            NoteVerb.SAVE -> alfred.saveNote(entry.noteId, entry.text)
            NoteVerb.DELETE -> alfred.deleteNote(entry.noteId)
            // A verb this build doesn't know (downgrade after a newer build queued
            // it): failing it keeps the queue moving.
            null -> NoteWriteResult.Rejected
        }

    companion object {
        private const val TAG = "PMA"

        // One mutex per process, held by the one instance.
        private val mutex = Mutex()

        /**
         * One at a time, in the order asked: two quick writes to a note must not
         * reach the queue the wrong way round, or the older one would win.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        internal fun defaultScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        @Volatile
        private var INSTANCE: NoteSync? = null

        fun getInstance(context: Context): NoteSync =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NoteSync(
                    AlfredRepository(context.applicationContext),
                    AppDatabase.getInstance(context.applicationContext).pendingNoteWriteDao()
                ).also { INSTANCE = it }
            }
    }
}
