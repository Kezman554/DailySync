package com.personalmorningalarm.data.phone

import android.content.Context
import android.util.Log
import com.personalmorningalarm.data.model.ListMerge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * Lists and notes that live on the phone: shopping lists, the to-do when Settings
 * puts it here, and notes. The same operations as the hub's lists (add, tick, drop,
 * delete a list, free text for notes), kept in [PhoneListDatabase].
 *
 * Takes no Alfred and no queue, so it can't send anything: a phone list never goes
 * through the offline queue and never calls the API.
 *
 * Every write runs in [scope], which outlives the screen: a screen's scope is cancelled
 * by the Back press that follows a tap. Writes also run one at a time in the order
 * asked, under [mutex]: Room suspends on its own threads, so a one-thread dispatcher
 * alone wouldn't stop two writes interleaving (a name checked free by two creates at
 * once, say). A failed write is logged, not thrown at the screen.
 */
class PhoneListStore(
    private val dao: PhoneListDao,
    private val scope: CoroutineScope = defaultScope(),
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val clock: () -> Long = System::currentTimeMillis
) : ShoppingMoveStore {

    /** Fair, so writes take it in the order [scope] started them. */
    private val mutex = Mutex()

    sealed interface CreateResult {
        data class Created(val list: PhoneList) : CreateResult
        /** A phone list of this kind already has that name, ignoring case. */
        data object Conflict : CreateResult
        data object Blank : CreateResult
    }

    // Shopping lists

    /** Every phone shopping list with its counts, oldest first. */
    fun shoppingLists(): Flow<List<PhoneListSummary>> =
        dao.observeSummaries(PhoneListKind.SHOPPING.name, sweepDay())

    /**
     * Makes a shopping list called [title], trimmed. Runs in [scope], so it finishes
     * even if the caller is cancelled; the answer is for whoever is still waiting.
     */
    suspend fun createShoppingList(title: String): CreateResult = scope.async {
        mutex.withLock { create(title) }
    }.await()

    private suspend fun create(title: String): CreateResult {
        val name = title.trim()
        if (name.isEmpty()) return CreateResult.Blank
        val taken = dao.listsOfKind(PhoneListKind.SHOPPING.name).any { it.title.equals(name, ignoreCase = true) }
        if (taken) return CreateResult.Conflict
        val list = PhoneList(
            id = UUID.randomUUID().toString(),
            kind = PhoneListKind.SHOPPING.name,
            title = name,
            createdAt = clock()
        )
        dao.insertList(list)
        Log.d(TAG, "Phone list created")
        return CreateResult.Created(list)
    }

    /** The list and everything on it. Not while it's moving to or from the hub. */
    fun deleteList(listId: String): Job = write("delete list") {
        if (moving(listId)) return@write
        dao.deleteList(listId)
    }

    // Items, on any phone list

    /** What's showing on [listId], in the order added: still to do, and ticks not yet cleared. */
    fun items(listId: String): Flow<List<PhoneListItem>> = dao.observeItems(listId, sweepDay())

    /** Adds [text] as one item: line breaks become spaces, as on the hub. Blank is ignored. */
    fun add(listId: String, text: String): Job = write("add") {
        val task = clean(text) ?: return@write
        if (moving(listId)) return@write
        if (listId == TODO_LIST_ID) ensureTodoList()
        dao.insertItem(PhoneListItem(listId = listId, text = task, addedOnEpochDay = todayDay()))
    }

    /**
     * Bought, or done: struck through until the next overnight clear ([SWEEP_TIME]),
     * or on a Keep ticked list until Clear bought.
     */
    fun tick(itemId: Long): Job = write("tick") {
        if (itemMoving(itemId)) return@write
        dao.tick(itemId, sweepDay())
    }

    /** Back to still to buy: a Keep ticked list's bought item, put back. */
    fun untick(itemId: Long): Job = write("untick") {
        if (itemMoving(itemId)) return@write
        dao.untick(itemId)
    }

    // Keep ticked (BJ): a setting on the list itself.

    /** Whether [listId] keeps its ticked items; false for a list that isn't there. */
    fun keepTicked(listId: String): Flow<Boolean> = dao.observeKeepTicked(listId).map { it == true }

    /**
     * Turns Keep ticked on or off. Off clears nothing at once: what was kept goes at
     * the next 03:07, as a hub list's does after its next sweep.
     */
    fun setKeepTicked(listId: String, keep: Boolean): Job = write("keep ticked") {
        if (moving(listId)) return@write
        dao.setKeepTicked(listId, keep, sweepDay())
    }

    /** "Clear bought": every ticked item on [listId] goes now. */
    fun clearBought(listId: String): Job = write("clear bought") {
        if (moving(listId)) return@write
        dao.deleteTickedOf(listId)
    }

    /**
     * "Copy into…" one of this phone's lists: [ListMerge]'s rule against what's on
     * [listId] now, its adds and ticks in one transaction, so it lands whole or not at
     * all. Null if the list isn't here, or is moving to or from the hub (it takes no
     * edits then). Runs in [scope] and throws a database failure to the caller.
     */
    suspend fun mergeInto(listId: String, theirs: List<ListMerge.Theirs>): ListMerge.Counts? = step {
        if (dao.list(listId) == null || dao.move(listId) != null) return@step null
        val today = sweepDay()
        val mine = dao.visibleItems(listId, today).map { ListMerge.Mine(it.id, it.text, it.ticked) }
        val plan = ListMerge.plan(theirs, mine)
        dao.applyMerge(rowsFor(listId, plan.adds.map { MovingItem(it.text, it.ticked) }), plan.ticks, today)
        plan.counts
    }

    /** No longer wanted: gone now. */
    fun drop(itemId: Long): Job = write("drop") {
        if (itemMoving(itemId)) return@write
        dao.deleteItem(itemId)
    }

    /**
     * The daily local clear: deletes items ticked before the last [SWEEP_TIME], on
     * every list but those with Keep ticked on. The
     * lists already hide them on read, from that moment; this is only the tidying,
     * so a screen calls it on opening and it never has to run on time. No job is
     * scheduled for it: there's nothing for a sleeping or killed phone to miss.
     */
    fun sweep(): Job = write("sweep") { dao.deleteTickedBefore(sweepDay()) }

    // The to-do

    /** The phone's to-do. Empty until something is added: the list is made on first add. */
    fun todoItems(): Flow<List<PhoneListItem>> = items(TODO_LIST_ID)

    fun addTodo(text: String): Job = add(TODO_LIST_ID, text)

    // Notes

    /** Newest first. */
    fun notes(): Flow<List<PhoneNote>> = dao.observeNotes()

    suspend fun note(noteId: String): PhoneNote? = dao.note(noteId)

    /** Keeps [text] exactly as given, replacing the note if it exists. */
    fun saveNote(noteId: String, text: String): Job = write("save note") {
        dao.putNote(PhoneNote(id = noteId, text = text, updatedAt = clock()))
    }

    fun deleteNote(noteId: String): Job = write("delete note") { dao.deleteNote(noteId) }

    private suspend fun ensureTodoList() {
        dao.insertListIfAbsent(
            PhoneList(id = TODO_LIST_ID, kind = PhoneListKind.TODO.name, title = "", createdAt = clock())
        )
    }

    /** The calendar day: what an item's added date is. */
    // Moving a shopping list to or from the hub (ShoppingMoveStore). Each step runs in
    // [scope] under [mutex], so it finishes even if the caller is cancelled and never
    // interleaves with an edit; unlike [write], a failure is thrown to the caller.

    override suspend fun shareSnapshot(listId: String): ShoppingMoveStore.Snapshot? = step {
        val list = dao.list(listId) ?: return@step null
        ShoppingMoveStore.Snapshot(
            title = list.title,
            items = dao.visibleItems(listId, sweepDay()).map { MovingItem(it.text, it.ticked) },
            keepTicked = list.keepTicked
        )
    }

    override suspend fun keepsTicked(listId: String): Boolean = step { dao.list(listId)?.keepTicked == true }

    override suspend fun move(phoneListId: String): ListMove? = step { dao.move(phoneListId) }

    override suspend fun pullBackFrom(hubId: String): ListMove? =
        step { dao.moveForHub(MoveDirection.PULL_BACK.name, hubId) }

    override suspend fun moves(): List<ListMove> = step { dao.moves() }

    override suspend fun beginShare(listId: String, title: String, hubIdsBefore: List<String>) = step {
        dao.insertMove(
            ListMove(
                phoneListId = listId,
                direction = MoveDirection.SHARE.name,
                title = title,
                hubId = null,
                hubIdsBefore = hubIdsBefore.joinToString("\n"),
                startedAt = clock()
            )
        )
    }

    override suspend fun recordUpload(listId: String, hubId: String) = step {
        dao.setMoveHubId(listId, hubId)
        Unit
    }

    override suspend fun finishShare(listId: String) = step { dao.finishShare(listId) }

    override suspend fun cancelMove(listId: String) = step {
        dao.deleteMove(listId)
        Unit
    }

    override suspend fun beginPullBack(
        title: String,
        hubId: String,
        items: List<MovingItem>,
        keepTicked: Boolean
    ): String = step {
        val list = PhoneList(
            id = UUID.randomUUID().toString(),
            kind = PhoneListKind.SHOPPING.name,
            title = title,
            createdAt = clock(),
            keepTicked = keepTicked
        )
        dao.beginPullBack(
            list,
            rowsFor(list.id, items),
            ListMove(
                phoneListId = list.id,
                direction = MoveDirection.PULL_BACK.name,
                title = title,
                hubId = hubId,
                startedAt = clock()
            )
        )
        list.id
    }

    override suspend fun finishPullBack(listId: String, items: List<MovingItem>?) = step {
        dao.finishPullBack(listId, items?.let { rowsFor(listId, it) })
    }

    override suspend fun abandonPullBack(listId: String) = step { dao.abandonPullBack(listId) }

    /** Rows for [items] on [listId], ticks kept: a ticked one clears at the next 03:07, as on the hub. */
    private fun rowsFor(listId: String, items: List<MovingItem>): List<PhoneListItem> {
        val added = todayDay()
        val sweep = sweepDay()
        return items.mapNotNull { item ->
            clean(item.text)?.let {
                PhoneListItem(
                    listId = listId,
                    text = it,
                    addedOnEpochDay = added,
                    tickedOnEpochDay = if (item.ticked) sweep else null
                )
            }
        }
    }

    private suspend fun <T> step(block: suspend () -> T): T = scope.async { mutex.withLock { block() } }.await()

    private suspend fun moving(listId: String): Boolean =
        (dao.move(listId) != null).also { if (it) Log.d(TAG, "Phone list is moving to or from the hub — edit ignored") }

    private suspend fun itemMoving(itemId: Long): Boolean = dao.listOfItem(itemId)?.let { moving(it) } ?: false

    private fun todayDay(): Long = now().toLocalDate().toEpochDay()

    /** The day the clock is in for ticks and clearing, which turns over at [SWEEP_TIME]. */
    private fun sweepDay(): Long = sweepDayOf(now()).toEpochDay()

    private fun write(what: String, block: suspend () -> Unit): Job = scope.launch {
        try {
            mutex.withLock { block() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.e(TAG, "Phone list $what failed", e)
        }
    }

    companion object {
        private const val TAG = "PMA"

        /**
         * When ticked items clear overnight: the hub's sweep time (its cron runs at
         * 03:07), so a phone list and a hub list grey and clear alike.
         */
        val SWEEP_TIME: LocalTime = LocalTime.of(3, 7)

        /**
         * The sweep day [at] falls in. It starts at [SWEEP_TIME], not midnight: a tick
         * at 23:00 stays greyed until 03:07, and one at 01:00 clears at 03:07 the same
         * night. An item ticked in an earlier sweep day than now's has been cleared.
         */
        fun sweepDayOf(at: LocalDateTime): LocalDate =
            at.minusHours(SWEEP_TIME.hour.toLong()).minusMinutes(SWEEP_TIME.minute.toLong()).toLocalDate()

        /** The phone to-do's fixed id: there is one, and it needs no name. */
        const val TODO_LIST_ID = "todo"

        private val NEWLINES = Regex("""\R+""")

        /** One line of text, or null if there's nothing in it. */
        internal fun clean(text: String): String? =
            text.replace(NEWLINES, " ").trim().takeIf { it.isNotEmpty() }

        @OptIn(ExperimentalCoroutinesApi::class)
        internal fun defaultScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        @Volatile
        private var INSTANCE: PhoneListStore? = null

        fun getInstance(context: Context): PhoneListStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PhoneListStore(PhoneListDatabase.getInstance(context).dao()).also { INSTANCE = it }
            }
    }
}
