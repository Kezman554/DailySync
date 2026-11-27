package com.personalmorningalarm.data.phone

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Lists whose hub copy is the one to show: shared (upload answered) or being pulled back. */
private const val HIDDEN_BY_MOVE =
    "SELECT phoneListId FROM list_moves WHERE direction = 'PULL_BACK' OR hubId IS NOT NULL"

/** Lists with Keep ticked on: the overnight clear never reaches their ticked items. */
private const val KEEPS_TICKED = "SELECT id FROM phone_lists WHERE keepTicked = 1"

/**
 * An item still showing: to do, ticked since the last 03:07 ([PhoneListStore.sweepDayOf]),
 * or ticked on a list that keeps its ticked items.
 */
private const val SHOWING =
    "(tickedOn IS NULL OR tickedOn >= :today OR listId IN ($KEEPS_TICKED))"

/** Everything kept in [PhoneListDatabase]. Only [PhoneListStore] uses it. */
@Dao
interface PhoneListDao {

    // Lists

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertList(list: PhoneList)

    /** Adds the list unless one with its id exists already. For the to-do's fixed id. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertListIfAbsent(list: PhoneList)

    @Query("SELECT * FROM phone_lists WHERE kind = :kind ORDER BY createdAt ASC, id ASC")
    suspend fun listsOfKind(kind: String): List<PhoneList>

    @Query("SELECT * FROM phone_lists WHERE id = :id")
    suspend fun list(id: String): PhoneList?

    /** Its items go with it (ON DELETE CASCADE). */
    @Query("DELETE FROM phone_lists WHERE id = :id")
    suspend fun deleteList(id: String): Int

    /**
     * Each list of [kind] with its counts, oldest first. Items ticked before
     * [today] are swept and so don't count. A list part-way through a move that
     * has the hub's copy showing instead ([ListMove]) is left out.
     */
    @Query(
        "SELECT l.id AS id, l.title AS title, " +
            "COUNT(i.id) AS total, " +
            "COALESCE(SUM(CASE WHEN i.id IS NOT NULL AND i.tickedOn IS NULL THEN 1 ELSE 0 END), 0) AS `left` " +
            "FROM phone_lists l " +
            "LEFT JOIN phone_list_items i ON i.listId = l.id " +
            "AND (i.tickedOn IS NULL OR i.tickedOn >= :today OR l.keepTicked = 1) " +
            "WHERE l.kind = :kind AND l.id NOT IN ($HIDDEN_BY_MOVE) " +
            "GROUP BY l.id ORDER BY l.createdAt ASC, l.id ASC"
    )
    fun observeSummaries(kind: String, today: Long): Flow<List<PhoneListSummary>>

    // Items

    @Insert
    suspend fun insertItem(item: PhoneListItem): Long

    /** Items still showing on [listId], in the order they were added. */
    @Query("SELECT * FROM phone_list_items WHERE listId = :listId AND $SHOWING ORDER BY id ASC")
    fun observeItems(listId: String, today: Long): Flow<List<PhoneListItem>>

    @Query("SELECT * FROM phone_list_items WHERE listId = :listId ORDER BY id ASC")
    suspend fun allItems(listId: String): List<PhoneListItem>

    /** Ticks one row, once: an item already ticked keeps the day it was ticked. */
    @Query("UPDATE phone_list_items SET tickedOn = :today WHERE id = :id AND tickedOn IS NULL")
    suspend fun tick(id: Long, today: Long): Int

    @Query("DELETE FROM phone_list_items WHERE id = :id")
    suspend fun deleteItem(id: Long): Int

    @Query("SELECT listId FROM phone_list_items WHERE id = :id")
    suspend fun listOfItem(id: Long): String?

    /** The overnight sweep: items ticked on an earlier day than [today], except on Keep ticked lists. */
    @Query(
        "DELETE FROM phone_list_items WHERE tickedOn IS NOT NULL AND tickedOn < :today " +
            "AND listId NOT IN ($KEEPS_TICKED)"
    )
    suspend fun deleteTickedBefore(today: Long): Int

    /** Items still showing on [listId], in order: what a share uploads, and what a merge adds to. */
    @Query("SELECT * FROM phone_list_items WHERE listId = :listId AND $SHOWING ORDER BY id ASC")
    suspend fun visibleItems(listId: String, today: Long): List<PhoneListItem>

    /** Back to still to buy. */
    @Query("UPDATE phone_list_items SET tickedOn = NULL WHERE id = :id")
    suspend fun untick(id: Long): Int

    /** "Clear bought": every ticked item on [listId] goes, whenever it was ticked. */
    @Query("DELETE FROM phone_list_items WHERE listId = :listId AND tickedOn IS NOT NULL")
    suspend fun deleteTickedOf(listId: String): Int

    @Query("SELECT keepTicked FROM phone_lists WHERE id = :listId")
    fun observeKeepTicked(listId: String): Flow<Boolean?>

    @Query("UPDATE phone_lists SET keepTicked = :keep WHERE id = :listId")
    suspend fun updateKeepTicked(listId: String, keep: Boolean): Int

    /** Ticked items from before [today] count as ticked [today]: they clear at the next 03:07, not at once. */
    @Query("UPDATE phone_list_items SET tickedOn = :today WHERE listId = :listId AND tickedOn < :today")
    suspend fun restampTicked(listId: String, today: Long): Int

    /**
     * Keep ticked on or off. Turning it off clears nothing by itself, as on the hub:
     * what was kept is re-dated to [today], so it goes at the next 03:07.
     */
    @Transaction
    suspend fun setKeepTicked(listId: String, keep: Boolean, today: Long) {
        if (!keep) restampTicked(listId, today)
        updateKeepTicked(listId, keep)
    }

    /** A merge ([com.personalmorningalarm.data.model.ListMerge]): its adds and ticks, all or none. */
    @Transaction
    suspend fun applyMerge(adds: List<PhoneListItem>, ticks: List<Long>, today: Long) {
        insertItems(adds)
        ticks.forEach { tick(it, today) }
    }

    @Insert
    suspend fun insertItems(items: List<PhoneListItem>)

    @Query("DELETE FROM phone_list_items WHERE listId = :listId")
    suspend fun deleteItemsOf(listId: String): Int

    // Moves between the phone and the hub

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMove(move: ListMove)

    @Query("SELECT * FROM list_moves WHERE phoneListId = :phoneListId")
    suspend fun move(phoneListId: String): ListMove?

    @Query("SELECT * FROM list_moves WHERE direction = :direction AND hubId = :hubId")
    suspend fun moveForHub(direction: String, hubId: String): ListMove?

    @Query("SELECT * FROM list_moves ORDER BY startedAt ASC")
    suspend fun moves(): List<ListMove>

    @Query("UPDATE list_moves SET hubId = :hubId WHERE phoneListId = :phoneListId")
    suspend fun setMoveHubId(phoneListId: String, hubId: String): Int

    @Query("DELETE FROM list_moves WHERE phoneListId = :phoneListId")
    suspend fun deleteMove(phoneListId: String): Int

    /** A share is done: the phone copy and its move record go together. */
    @Transaction
    suspend fun finishShare(phoneListId: String) {
        deleteList(phoneListId)
        deleteMove(phoneListId)
    }

    /** A pull-back begins: the list, its items as last read, and the move record, all or none. */
    @Transaction
    suspend fun beginPullBack(list: PhoneList, items: List<PhoneListItem>, move: ListMove) {
        insertList(list)
        insertItems(items)
        insertMove(move)
    }

    /** A pull-back is done: the hub's answer (or, with null, the snapshot) stays and the list shows. */
    @Transaction
    suspend fun finishPullBack(phoneListId: String, items: List<PhoneListItem>?) {
        if (items != null) {
            deleteItemsOf(phoneListId)
            insertItems(items)
        }
        deleteMove(phoneListId)
    }

    /** A pull-back that didn't happen: the snapshot list goes, the hub's copy is still the list. */
    @Transaction
    suspend fun abandonPullBack(phoneListId: String) {
        deleteList(phoneListId)
        deleteMove(phoneListId)
    }

    // Notes

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putNote(note: PhoneNote)

    @Query("SELECT * FROM phone_notes WHERE id = :id")
    suspend fun note(id: String): PhoneNote?

    /** Newest first, as the hub lists its notes. */
    @Query("SELECT * FROM phone_notes ORDER BY updatedAt DESC, id ASC")
    fun observeNotes(): Flow<List<PhoneNote>>

    @Query("DELETE FROM phone_notes WHERE id = :id")
    suspend fun deleteNote(id: String): Int
}
