package com.personalmorningalarm.data.remote

import android.content.Context
import android.util.Log
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.model.ShoppingSharing
import com.personalmorningalarm.data.phone.ListMove
import com.personalmorningalarm.data.phone.MoveDirection
import com.personalmorningalarm.data.phone.MovingItem
import com.personalmorningalarm.data.phone.PhoneListStore
import com.personalmorningalarm.data.phone.ShoppingMoveStore
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.profile.ProfileSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sharing a shopping list: setting the hub's flag on a hub list, and moving a list
 * between the phone and the hub. A move is a move, never a copy, so the two can't
 * drift: sharing a phone list uploads it and then deletes the phone copy; unsharing
 * a list whose owner keeps lists on the phone (Jess) pulls it back and the hub's copy
 * goes.
 *
 * Every move needs the hub there and then. Nothing is queued: with no hub the answer
 * is [Outcome.Unreachable], and nothing has moved.
 *
 * Items are never lost, even if the network drops between steps. Each step is written
 * down in [store] before the next call goes out ([ListMove]):
 *  - share: the upload's id is recorded before the phone copy is deleted. A lost
 *    answer is settled by looking for the list it would have made (the upload isn't
 *    idempotent, so it is never just repeated); if the hub can't be asked, the move
 *    waits, the phone copy kept and locked, and [resume] settles it later.
 *  - pull-back: the hub's items are saved to a hidden phone list BEFORE the pull-back
 *    call, because that call deletes the hub's copy as it answers. If the answer is
 *    lost, the hub is asked whether the list is still there: still there, the phone
 *    copy is dropped (nothing moved); gone, the saved items stay.
 *
 * Calls run one at a time ([mutex]) and can't be cancelled part-way: leaving the screen
 * must not strand a move between two steps.
 */
class ShoppingShare(
    private val alfred: AlfredRepository,
    private val sync: ShoppingSync,
    private val store: ShoppingMoveStore,
    private val profile: () -> Profile?
) {

    sealed interface Outcome {
        /** The phone list is now the hub list [hubId], shared. */
        data class MovedToHub(val hubId: String, val title: String) : Outcome
        /**
         * The hub list is now the phone list [phoneListId]. [hubConfirmedGone] is false
         * only if the hub couldn't be asked afterwards whether its copy is gone.
         */
        data class MovedToPhone(val phoneListId: String, val title: String, val hubConfirmedGone: Boolean) : Outcome
        /** The flag is set; the list stays where it is. */
        data class FlagSet(val shared: Boolean) : Outcome
        /** No hub. Nothing moved and nothing waits. */
        data object Unreachable : Outcome
        /**
         * The hub stopped answering part-way. Nothing is lost: the list stays as it was
         * on the phone or the hub, locked, until [resume] can finish or undo the move.
         */
        data object Unfinished : Outcome
        /** Edits to this list are still waiting to sync; a pull-back would leave them stranded. */
        data object ChangesWaiting : Outcome
        /** Only the list's owner can change its sharing. */
        data object NotOwner : Outcome
        /** The list isn't there any more. */
        data object Gone : Outcome
        /** The hub refused (a 4xx). Nothing moved. */
        data object Refused : Outcome
        /** This list is already moving. */
        data object Busy : Outcome
    }

    /** Shares the phone list [phoneListId]: uploads it to the hub, shared, then deletes it here. */
    suspend fun sharePhoneList(phoneListId: String): Outcome = exclusive(Outcome.Unfinished) {
        val me = profile() ?: return@exclusive Outcome.NotOwner
        if (store.move(phoneListId) != null) return@exclusive Outcome.Busy
        val snapshot = store.shareSnapshot(phoneListId) ?: return@exclusive Outcome.Gone
        // The hub's lists before the upload: how a lost answer is recognised later.
        val before = alfred.liveShoppingLists() ?: return@exclusive Outcome.Unreachable
        store.beginShare(phoneListId, snapshot.title, before.mapNotNull { it.id })
        val upload = alfred.uploadShoppingList(
            snapshot.title,
            me.wireName,
            snapshot.items.map { ShoppingUploadItem(it.text, it.ticked) }
        )
        when (upload) {
            is UploadResult.Uploaded -> completeShare(phoneListId, upload.hubId, snapshot.title)
            is UploadResult.Refused -> {
                store.cancelMove(phoneListId)
                Outcome.Refused
            }
            UploadResult.Unreachable -> settleShare(store.move(phoneListId) ?: return@exclusive Outcome.Gone, me)
        }
    }

    /**
     * Sets a hub list's sharing to [shared], as [ShoppingSharing.toggle] says: the flag,
     * or for a list its owner keeps on the phone, a pull-back.
     */
    suspend fun setShared(list: ShoppingListSummaryDto, shared: Boolean): Outcome = exclusive(Outcome.Unfinished) {
        val me = profile() ?: return@exclusive Outcome.NotOwner
        val hubId = list.id ?: return@exclusive Outcome.Gone
        when (ShoppingSharing.toggle(me, list, shared)) {
            ShoppingSharing.Toggle.NOT_OWNER -> Outcome.NotOwner
            ShoppingSharing.Toggle.SET_FLAG -> when (alfred.setShoppingShared(hubId, shared)) {
                ShareFlagResult.Done -> Outcome.FlagSet(shared)
                ShareFlagResult.Gone -> Outcome.Gone
                ShareFlagResult.Unreachable -> Outcome.Unreachable
            }
            ShoppingSharing.Toggle.PULL_BACK -> pullBack(hubId, list.title ?: hubId, list.keepTicked == true)
        }
    }

    /**
     * Finishes or undoes every move left part-way. Safe to call at any time; quiet
     * when the hub still can't be asked (the moves just wait for next time).
     */
    suspend fun resume() {
        val me = profile() ?: return
        exclusive(Unit) {
            for (move in store.moves()) {
                val outcome = when (MoveDirection.entries.firstOrNull { it.name == move.direction }) {
                    MoveDirection.SHARE -> move.hubId
                        ?.let { completeShare(move.phoneListId, it, move.title) }
                        ?: settleShare(move, me)
                    MoveDirection.PULL_BACK -> settlePullBack(move).also { outcome ->
                        // The hub's copy is gone, so nothing queued for it can land.
                        if (outcome is Outcome.MovedToPhone) move.hubId?.let { id -> sync.holding { it.forget(id) } }
                    }
                    // A direction this build doesn't know: leave it for the build that wrote it.
                    null -> continue
                }
                Log.d(TAG, "Shopping move resumed (${move.direction}): ${outcome::class.simpleName}")
            }
        }
    }

    private suspend fun pullBack(hubId: String, title: String, keepTicked: Boolean): Outcome {
        if (store.pullBackFrom(hubId) != null) return Outcome.Busy
        // Whatever is queued for the list goes first; anything still queued after that
        // would be stranded, so the move waits for it.
        if (sync.hasPending()) sync.flush()
        return sync.holding { queue ->
            if (queue.pendingFor(hubId) > 0) return@holding Outcome.ChangesWaiting
            val items = when (val live = alfred.liveShoppingList(hubId)) {
                is LiveListResult.Found -> live.items
                LiveListResult.Gone -> return@holding Outcome.Gone
                LiveListResult.Unreachable -> return@holding Outcome.Unreachable
            }
            // Saved here first: the pull-back deletes the hub's copy as it answers.
            val phoneListId = store.beginPullBack(title, hubId, items.toMoving(), keepTicked)
            val outcome = when (val result = alfred.pullBackShoppingList(hubId)) {
                is PullBackResult.PulledBack -> {
                    store.finishPullBack(phoneListId, result.items.toMoving())
                    Outcome.MovedToPhone(phoneListId, title, hubConfirmedGone = confirmGone(hubId))
                }
                // Gone between the read and now. The read's items are kept, so nothing is lost.
                PullBackResult.Gone -> {
                    store.finishPullBack(phoneListId, null)
                    Outcome.MovedToPhone(phoneListId, title, hubConfirmedGone = true)
                }
                is PullBackResult.Refused -> {
                    store.abandonPullBack(phoneListId)
                    Outcome.Refused
                }
                PullBackResult.Unreachable -> settlePullBack(store.move(phoneListId) ?: return@holding Outcome.Gone)
            }
            if (outcome is Outcome.MovedToPhone) queue.forget(hubId)
            outcome
        }
    }

    /**
     * The upload is in: record it, carry Keep ticked across, then delete the phone copy.
     * Each step survives the next failing. Keep ticked goes before the delete, while the
     * phone copy still says what it was, so a resume tries it again; with no answer the
     * list is shared without it, and its owner can turn it on there.
     */
    private suspend fun completeShare(phoneListId: String, hubId: String, title: String): Outcome {
        store.recordUpload(phoneListId, hubId)
        if (store.keepsTicked(phoneListId) && alfred.setShoppingKeepTicked(hubId, true) != ShareFlagResult.Done) {
            Log.w(TAG, "Shared, but Keep ticked didn't reach the hub")
        }
        try {
            store.finishShare(phoneListId)
        } catch (e: Exception) {
            // The hub has the list and the phone copy is already hidden ([recordUpload]):
            // it has moved. [resume] deletes the leftover copy.
            Log.w(TAG, "Shared, but the phone copy wasn't deleted yet — resume will", e)
        }
        return Outcome.MovedToHub(hubId, title)
    }

    /**
     * A share whose upload answer never came. A new list of ours with this title that
     * wasn't on the hub before is the upload's; none means it didn't happen.
     */
    private suspend fun settleShare(move: ListMove, me: Profile): Outcome {
        val lists = alfred.liveShoppingLists() ?: return Outcome.Unfinished
        val before = move.hubIdsBefore.split('\n').filter { it.isNotEmpty() }.toSet()
        val made = lists.firstOrNull {
            it.id != null && it.id !in before && it.title == move.title && ShoppingSharing.isMine(it, me)
        }
        if (made?.id != null) return completeShare(move.phoneListId, made.id, move.title)
        store.cancelMove(move.phoneListId)
        return Outcome.Unreachable
    }

    /**
     * A pull-back whose answer never came: the hub still having the list means it
     * didn't happen, and the saved copy goes; the list being gone means it did, and the
     * saved items stay. With no hub to ask, the move waits. Never takes the queue lock:
     * [pullBack] calls this already holding it, so the caller clears the queue.
     */
    private suspend fun settlePullBack(move: ListMove): Outcome {
        val hubId = move.hubId
        if (hubId == null) {
            store.abandonPullBack(move.phoneListId)
            return Outcome.Gone
        }
        return when (alfred.liveShoppingList(hubId)) {
            is LiveListResult.Found -> {
                store.abandonPullBack(move.phoneListId)
                Outcome.Unreachable
            }
            LiveListResult.Gone -> {
                store.finishPullBack(move.phoneListId, null)
                Outcome.MovedToPhone(move.phoneListId, move.title, hubConfirmedGone = true)
            }
            LiveListResult.Unreachable -> Outcome.Unfinished
        }
    }

    /** After a pull-back, the spec's last step: check the hub really has no copy left. */
    private suspend fun confirmGone(hubId: String): Boolean =
        when (alfred.liveShoppingList(hubId)) {
            LiveListResult.Gone -> true
            is LiveListResult.Found -> {
                Log.w(TAG, "Pull-back answered, but the hub still lists it")
                false
            }
            LiveListResult.Unreachable -> false
        }

    /**
     * One move at a time, run to the end even if the caller goes away. A step that
     * fails on the phone leaves the move recorded where it got to, so it reads as
     * [Outcome.Unfinished] and [resume] takes it from there.
     */
    private suspend fun <T> exclusive(onFailure: T, block: suspend () -> T): T =
        withContext(NonCancellable) {
            mutex.withLock {
                try {
                    block()
                } catch (e: Exception) {
                    Log.e(TAG, "Shopping move stopped part-way", e)
                    onFailure
                }
            }
        }

    private fun List<com.personalmorningalarm.data.model.ShoppingItemDto>.toMoving(): List<MovingItem> =
        mapNotNull { item -> item.text?.let { MovingItem(it, item.ticked == true) } }

    companion object {
        private const val TAG = "PMA"

        private val mutex = Mutex()

        @Volatile
        private var INSTANCE: ShoppingShare? = null

        fun getInstance(context: Context): ShoppingShare =
            INSTANCE ?: synchronized(this) {
                val app = context.applicationContext
                INSTANCE ?: ShoppingShare(
                    AlfredRepository(app),
                    ShoppingSync.getInstance(app),
                    PhoneListStore.getInstance(app),
                    ProfileSettings(app)::profile
                ).also { INSTANCE = it }
            }
    }
}
