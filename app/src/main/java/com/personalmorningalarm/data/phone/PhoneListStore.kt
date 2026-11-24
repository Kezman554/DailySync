package com.personalmorningalarm.data.phone

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
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
    private val today: () -> LocalDate = LocalDate::now,
    private val clock: () -> Long = System::currentTimeMillis
) {

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
        dao.observeSummaries(PhoneListKind.SHOPPING.name, todayDay())

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

    /** The list and everything on it. */
    fun deleteList(listId: String): Job = write("delete list") { dao.deleteList(listId) }

    // Items, on any phone list

    /** What's showing on [listId], in the order added: still to do, and today's ticks. */
    fun items(listId: String): Flow<List<PhoneListItem>> = dao.observeItems(listId, todayDay())

    /** Adds [text] as one item: line breaks become spaces, as on the hub. Blank is ignored. */
    fun add(listId: String, text: String): Job = write("add") {
        val task = clean(text) ?: return@write
        if (listId == TODO_LIST_ID) ensureTodoList()
        dao.insertItem(PhoneListItem(listId = listId, text = task, addedOnEpochDay = todayDay()))
    }

    /** Bought, or done: struck through for the rest of today, gone tomorrow. */
    fun tick(itemId: Long): Job = write("tick") { dao.tick(itemId, todayDay()) }

    /** No longer wanted: gone now. */
    fun drop(itemId: Long): Job = write("drop") { dao.deleteItem(itemId) }

    /**
     * Clears items ticked before today. The lists already hide them on read; this is
     * only the tidying, so a screen calls it on opening and it never has to run on time.
     */
    fun sweep(): Job = write("sweep") { dao.deleteTickedBefore(todayDay()) }

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

    private fun todayDay(): Long = today().toEpochDay()

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
