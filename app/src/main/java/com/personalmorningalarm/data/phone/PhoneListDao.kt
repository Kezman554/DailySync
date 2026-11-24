package com.personalmorningalarm.data.phone

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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
     * [today] are swept and so don't count.
     */
    @Query(
        "SELECT l.id AS id, l.title AS title, " +
            "COUNT(i.id) AS total, " +
            "COALESCE(SUM(CASE WHEN i.id IS NOT NULL AND i.tickedOn IS NULL THEN 1 ELSE 0 END), 0) AS `left` " +
            "FROM phone_lists l " +
            "LEFT JOIN phone_list_items i ON i.listId = l.id AND (i.tickedOn IS NULL OR i.tickedOn >= :today) " +
            "WHERE l.kind = :kind " +
            "GROUP BY l.id ORDER BY l.createdAt ASC, l.id ASC"
    )
    fun observeSummaries(kind: String, today: Long): Flow<List<PhoneListSummary>>

    // Items

    @Insert
    suspend fun insertItem(item: PhoneListItem): Long

    /** Items still showing on [listId], in the order they were added. */
    @Query(
        "SELECT * FROM phone_list_items WHERE listId = :listId " +
            "AND (tickedOn IS NULL OR tickedOn >= :today) ORDER BY id ASC"
    )
    fun observeItems(listId: String, today: Long): Flow<List<PhoneListItem>>

    @Query("SELECT * FROM phone_list_items WHERE listId = :listId ORDER BY id ASC")
    suspend fun allItems(listId: String): List<PhoneListItem>

    /** Ticks one row, once: an item already ticked keeps the day it was ticked. */
    @Query("UPDATE phone_list_items SET tickedOn = :today WHERE id = :id AND tickedOn IS NULL")
    suspend fun tick(id: Long, today: Long): Int

    @Query("DELETE FROM phone_list_items WHERE id = :id")
    suspend fun deleteItem(id: Long): Int

    /** The overnight sweep: items ticked on an earlier day than [today]. */
    @Query("DELETE FROM phone_list_items WHERE tickedOn IS NOT NULL AND tickedOn < :today")
    suspend fun deleteTickedBefore(today: Long): Int

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
