package com.personalmorningalarm.data.remote

import android.content.Context
import android.util.Log
import com.personalmorningalarm.data.model.ListMerge
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.model.ShoppingSharing
import com.personalmorningalarm.data.phone.ListHome
import com.personalmorningalarm.data.phone.PhoneListStore
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.profile.ProfileSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * "Copy into…" (BJ): a list someone has shared with you, copied into a new list of
 * your own or merged into one you have. The copy is yours and shared with nobody, and
 * it doesn't stay linked: it's a snapshot, and copying again merges again.
 *
 * The rule is [ListMerge]'s, the same for both stores. A new list lives where your
 * unshared lists do ([Profile.unsharedListHome]): Nick's on the Pi, Jess's on her
 * phone.
 *
 * Their list is read live: a copy is of what they have now, never a saved copy.
 *
 * Into a phone list it's one transaction. Into a Pi list it's several requests (each
 * add, then each tick, which needs the added item's line), so it can stop part-way.
 * Copying into the same list again finishes it without doubling anything: the plan is
 * made again from both lists as they are, so what landed is "already there"; adds and
 * ticks are keyed ([AddKey]), and a retry within the app's life sends the same keys,
 * so even an add whose answer was lost lands once. The line said afterwards is the
 * first attempt's, so a retry doesn't report "added 0".
 */
class ShoppingCopy(
    private val alfred: AlfredRepository,
    private val phone: PhoneListStore,
    private val profile: () -> Profile?
) {

    /** Where a copy can go. */
    sealed interface Target {
        val title: String

        data class Hub(val id: String, override val title: String) : Target
        data class Phone(val id: String, override val title: String) : Target
        /** A new list called [title], made where this person's unshared lists live. */
        data class NewList(override val title: String) : Target
    }

    sealed interface Outcome {
        /** Done. [sharer] is whose list it was, for "already bought by Jess". */
        data class Done(
            val counts: ListMerge.Counts,
            val sharer: Profile?,
            val title: String,
            val listId: String,
            val home: ListHome
        ) : Outcome

        /**
         * The hub stopped answering part-way into the Pi list [listId]: some of it is
         * there. Copying into that list again finishes it.
         */
        data class Unfinished(val title: String, val listId: String) : Outcome

        /** The hub couldn't be reached: nothing was copied. */
        data object Unreachable : Outcome

        /** A list of yours already has that name. */
        data object NameTaken : Outcome

        /** Their list, or yours, isn't there any more (or yours is moving to or from the hub). */
        data object Gone : Outcome

        /** Not set up as anyone yet: there's no "your own" to copy into. */
        data object NoProfile : Outcome
    }

    /** A Pi merge part-way: its keys, and the story its first attempt told. */
    private data class Run(val id: String, val counts: ListMerge.Counts)

    /** By "source→target". In memory: after a restart a retry is still safe, by the re-read plan. */
    private val runs = mutableMapOf<String, Run>()

    /**
     * Your own lists, other than [source]: the Pi's from discovery (its saved copy will
     * do for a menu), then the phone's. Sharing doesn't matter: they're yours.
     */
    suspend fun targets(source: ShoppingListSummaryDto): List<Target> {
        val me = profile() ?: return emptyList()
        val hub = alfred.getShoppingLists().dataOrNull().orEmpty()
            .filter { it.id != null && it.id != source.id && ShoppingSharing.isMine(it, me) }
            .map { Target.Hub(it.id!!, it.title ?: it.id) }
        val onPhone = phone.shoppingLists().first().map { Target.Phone(it.id, it.title) }
        return hub + onPhone
    }

    /** Copies [source] into [target]. Runs to the end even if the caller goes. */
    suspend fun copy(source: ShoppingListSummaryDto, target: Target): Outcome = withContext(NonCancellable) {
        mutex.withLock {
            try {
                run(source, target)
            } catch (e: Exception) {
                // The phone's store failing (its transaction rolls back), or a bug:
                // nothing half-done on the phone, and a Pi merge is safe to run again.
                Log.e(TAG, "Copy into stopped", e)
                (target as? Target.Hub)?.let { Outcome.Unfinished(it.title, it.id) } ?: Outcome.Gone
            }
        }
    }

    private suspend fun run(source: ShoppingListSummaryDto, target: Target): Outcome {
        val me = profile() ?: return Outcome.NoProfile
        val sourceId = source.id ?: return Outcome.Gone
        val sharer = ShoppingSharing.ownerOf(source)
        val theirs = when (val live = alfred.liveShoppingList(sourceId)) {
            is LiveListResult.Found -> live.items.mapNotNull { item ->
                item.text?.takeIf { it.isNotBlank() }?.let { ListMerge.Theirs(it.trim(), item.ticked == true) }
            }
            LiveListResult.Gone -> return Outcome.Gone
            LiveListResult.Unreachable -> return Outcome.Unreachable
        }

        fun done(counts: ListMerge.Counts, title: String, id: String, home: ListHome) =
            Outcome.Done(counts, sharer, title, id, home)

        return when (target) {
            is Target.Phone -> phone.mergeInto(target.id, theirs)
                ?.let { done(it, target.title, target.id, ListHome.PHONE) } ?: Outcome.Gone
            is Target.Hub -> mergeIntoHub(sourceId, target.id, target.title, theirs, ::done)
            is Target.NewList -> when (me.unsharedListHome) {
                ListHome.PHONE -> when (val made = phone.createShoppingList(target.title)) {
                    is PhoneListStore.CreateResult.Created -> phone.mergeInto(made.list.id, theirs)
                        ?.let { done(it, made.list.title, made.list.id, ListHome.PHONE) } ?: Outcome.Gone
                    PhoneListStore.CreateResult.Conflict, PhoneListStore.CreateResult.Blank -> Outcome.NameTaken
                }
                // Made with ?for= (every shopping request carries it): owned, unshared.
                ListHome.HUB -> when (val made = alfred.createShoppingList(target.title.trim())) {
                    is ShoppingCreateResult.Created -> made.list.id
                        ?.let { mergeIntoHub(sourceId, it, made.list.title ?: target.title, theirs, ::done) }
                        ?: Outcome.Unreachable
                    ShoppingCreateResult.Conflict -> Outcome.NameTaken
                    ShoppingCreateResult.Unreachable -> Outcome.Unreachable
                }
            }
        }
    }

    /**
     * The rule into a Pi list: read it, add what's missing, read it again (an added
     * item has no line to tick until then), tick what they've bought. Every request
     * is keyed by [Run.id] and what it targets, so a retry repeats a request exactly
     * or not at all.
     */
    private suspend fun mergeIntoHub(
        sourceId: String,
        targetId: String,
        title: String,
        theirs: List<ListMerge.Theirs>,
        done: (ListMerge.Counts, String, String, ListHome) -> Outcome
    ): Outcome {
        val unfinished = Outcome.Unfinished(title, targetId)
        val runKey = "$sourceId→$targetId"
        val first = when (val read = readHub(targetId)) {
            null -> return Outcome.Gone
            Unread -> return if (runs.containsKey(runKey)) unfinished else Outcome.Unreachable
            is Read -> ListMerge.plan(theirs, read.mine)
        }
        val run = runs.getOrPut(runKey) { Run(UUID.randomUUID().toString(), first.counts) }

        var plan = first
        if (first.adds.isNotEmpty()) {
            for (add in first.adds) {
                // Not landed (no answer, or a 404 if the list went): the next try reads it again.
                if (alfred.addShoppingItem(targetId, add.text, keyFor(run, "add", add.text)) != ShoppingWriteResult.Done) {
                    return unfinished
                }
            }
            // The ticked ones arrived unticked: their lines are known only now.
            plan = when (val read = readHub(targetId)) {
                null -> return Outcome.Gone
                Unread -> return unfinished
                is Read -> ListMerge.plan(theirs, read.mine)
            }
        }
        for (line in plan.ticks.filterNotNull()) {
            val result = alfred.tickShoppingItem(targetId, line, keyFor(run, "tick", line))
            // A 404 here is a line that changed under us: the next try plans afresh.
            if (result != ShoppingWriteResult.Done) return unfinished
        }
        runs.remove(runKey)
        Log.d(TAG, "Copy into a Pi list done: ${run.counts}")
        return done(run.counts, title, targetId, ListHome.HUB)
    }

    private sealed interface HubRead
    private data class Read(val mine: List<ListMerge.Mine<String?>>) : HubRead
    private data object Unread : HubRead

    /** The Pi list as it is now; [Unread] with no answer, null if it's gone. */
    private suspend fun readHub(listId: String): HubRead? = when (val live = alfred.liveShoppingList(listId)) {
        is LiveListResult.Found -> Read(
            live.items.filter { !it.text.isNullOrBlank() }
                .map { ListMerge.Mine(it.line, it.text!!, it.ticked == true) }
        )
        LiveListResult.Gone -> null
        LiveListResult.Unreachable -> Unread
    }

    private fun keyFor(run: Run, verb: String, target: String): String =
        UUID.nameUUIDFromBytes("${run.id}|$verb|$target".toByteArray()).toString()

    companion object {
        private const val TAG = "PMA"

        private val mutex = Mutex()

        @Volatile
        private var INSTANCE: ShoppingCopy? = null

        fun getInstance(context: Context): ShoppingCopy =
            INSTANCE ?: synchronized(this) {
                val app = context.applicationContext
                INSTANCE ?: ShoppingCopy(
                    AlfredRepository(app),
                    PhoneListStore.getInstance(app),
                    ProfileSettings(app)::profile
                ).also { INSTANCE = it }
            }
    }
}
