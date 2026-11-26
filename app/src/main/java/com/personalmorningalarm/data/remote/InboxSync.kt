package com.personalmorningalarm.data.remote

import android.content.Context
import android.util.Log
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.dao.PendingInboxWriteDao
import com.personalmorningalarm.data.entity.PendingInboxWrite
import com.personalmorningalarm.data.model.Inbox
import com.personalmorningalarm.data.model.InboxCaptureDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Store-and-forward for inbox captures. The third queue in the [ChalkboardSync] /
 * [ShoppingSync] family: one verb, no targeting key. Captures are strictly ordered —
 * the vault reads them as a stream of thoughts — so the FIFO matters here even
 * though nothing collides.
 *
 * Every capture lands once. POST /inbox writes a new file on every call, so a
 * capture is never simply sent again after a lost answer:
 *  - every capture, live ones too ([capture]), is a row here before it's sent, and
 *    the row's [PendingInboxWrite.attemptedAt] is set before each send and cleared
 *    if the send never got a connection ([SendMarker]): "never left the phone" is
 *    safe to send again, anything else may have arrived;
 *  - before resending one that may have arrived, the flush looks on the hub for a
 *    file with exactly its text stamped since that send ([Inbox.landed]). Found, it
 *    was delivered. Not found and sent under [SETTLE_MILLIS] ago, it waits: the Pi
 *    may still be writing it. Otherwise it's sent again.
 */
class InboxSync(
    private val alfred: AlfredRepository,
    private val dao: PendingInboxWriteDao,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** Everything queued, refused entries included. */
    val queue: Flow<List<PendingInboxWrite>> = dao.observeAll()

    /** Flushes that changed something, so an open inbox screen can reconcile. */
    private val _flushes = MutableSharedFlow<FlushOutcome>(extraBufferCapacity = 4)
    val flushes: SharedFlow<FlushOutcome> = _flushes.asSharedFlow()

    data class FlushOutcome(val delivered: Int, val refused: Int, val remaining: Int) {
        val changedAnything: Boolean get() = delivered > 0 || refused > 0
    }

    suspend fun enqueue(text: String) {
        dao.insert(PendingInboxWrite(text = text, createdAt = clock()))
        Log.d(TAG, "Inbox capture queued for sync")
    }

    /**
     * Captures [text] now. It's a queue row first, marked as being sent, so if the
     * answer is lost (or the app dies waiting) a later flush looks for it before
     * sending it again. Delivered or refused, the row goes; unreachable, it stays
     * queued. A refusal isn't kept for the notice: the screen says so at once.
     */
    suspend fun capture(text: String): InboxWriteResult = mutex.withLock {
        val now = clock()
        val id = dao.insert(PendingInboxWrite(text = text, createdAt = now, attemptedAt = now))
        val sent = SendMarker()
        val result = alfred.captureToInbox(text, sent)
        when (result) {
            InboxWriteResult.Done, InboxWriteResult.Rejected -> dao.deleteById(id)
            InboxWriteResult.Unreachable -> if (!sent.sent) dao.setAttempted(id, null)
        }
        result
    }

    /** Cheap guard for the app-foreground trigger: don't touch the network for an empty queue. */
    suspend fun hasPending(): Boolean = dao.countPending() > 0

    /** Clears the refused entries once their notice has been dismissed. */
    suspend fun dismissFailed() = dao.deleteFailed()

    /** Replays the whole queue in capture order. Safe to call speculatively. */
    suspend fun flush(): FlushOutcome {
        val outcome = mutex.withLock { runFlush() }
        if (outcome.changedAnything) _flushes.emit(outcome)
        return outcome
    }

    private suspend fun runFlush(): FlushOutcome {
        val pending = dao.getPending()
        var delivered = 0
        var refused = 0
        // The hub's captures, read once per flush and only if something may have arrived.
        var onHub: List<InboxCaptureDto>? = null
        // Files already matched to an entry this flush: two identical captures are two files.
        val claimed = mutableSetOf<String>()
        for (entry in pending) {
            val attemptedAt = entry.attemptedAt
            if (attemptedAt != null) {
                val captures = onHub ?: liveInbox()?.also { onHub = it } ?: run {
                    Log.d(TAG, "Can't check the inbox for an earlier send — captures stay queued")
                    return FlushOutcome(delivered, refused, dao.countPending())
                }
                val landed = Inbox.landed(captures, entry.text, attemptedAt, claimed)
                if (landed != null) {
                    claimed += landed
                    dao.delete(entry)
                    delivered++
                    Log.d(TAG, "Queued capture was already on the hub ($landed) — not sent again")
                    continue
                }
                if (clock() - attemptedAt < SETTLE_MILLIS) {
                    Log.d(TAG, "Queued capture sent moments ago and not on the hub yet — waiting")
                    return FlushOutcome(delivered, refused, dao.countPending())
                }
            }
            when (send(entry)) {
                InboxWriteResult.Done -> {
                    dao.delete(entry)
                    delivered++
                }
                InboxWriteResult.Rejected -> {
                    // Alfred read this one and said no. Retrying it forever would
                    // stall every capture behind it, so it's failed and kept for
                    // the notice, and the rest still get their go.
                    dao.markFailed(entry.id)
                    refused++
                    Log.w(TAG, "Queued capture refused by Alfred — kept for the notice")
                }
                InboxWriteResult.Unreachable -> {
                    Log.d(TAG, "Alfred still unreachable — ${pending.size - delivered - refused} capture(s) stay queued")
                    return FlushOutcome(delivered, refused, dao.countPending())
                }
            }
        }
        if (delivered > 0 || refused > 0) {
            Log.d(TAG, "Inbox queue flushed: $delivered delivered, $refused refused")
        }
        return FlushOutcome(delivered, refused, dao.countPending())
    }

    /** Sends [entry], recording the attempt first and clearing it if nothing got out. */
    private suspend fun send(entry: PendingInboxWrite): InboxWriteResult {
        dao.setAttempted(entry.id, clock())
        val sent = SendMarker()
        val result = alfred.captureToInbox(entry.text, sent)
        if (result == InboxWriteResult.Unreachable && !sent.sent) dao.setAttempted(entry.id, null)
        return result
    }

    /** The hub's captures as they are now; a saved copy can't say what has landed since. */
    private suspend fun liveInbox(): List<InboxCaptureDto>? =
        (alfred.getInbox() as? AlfredResult.Fresh)?.data

    companion object {
        private const val TAG = "PMA"

        /**
         * How long a capture that went out without an answer may still be on its way.
         * The phone stops waiting at 15 seconds, but each write is a git transaction on
         * the Pi, queued behind any others, so it can land after that.
         */
        const val SETTLE_MILLIS = 2 * 60 * 1000L

        // One mutex per process, held by the one instance.
        private val mutex = Mutex()

        @Volatile
        private var INSTANCE: InboxSync? = null

        fun getInstance(context: Context): InboxSync =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: InboxSync(
                    AlfredRepository(context.applicationContext),
                    AppDatabase.getInstance(context.applicationContext).pendingInboxWriteDao()
                ).also { INSTANCE = it }
            }
    }
}
