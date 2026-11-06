package com.personalmorningalarm.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.personalmorningalarm.data.entity.PendingShoppingWrite
import kotlinx.coroutines.flow.Flow

/** Mirrors [PendingChalkboardWriteDao], scoped by [PendingShoppingWrite.listId] where it matters. */
@Dao
interface PendingShoppingWriteDao {

    @Insert
    suspend fun insert(write: PendingShoppingWrite): Long

    @Delete
    suspend fun delete(write: PendingShoppingWrite)

    /** Replay order across every list: id is AUTOINCREMENT, so this is insertion (FIFO) order. */
    @Query("SELECT * FROM pending_shopping_writes WHERE failed = 0 ORDER BY id ASC")
    suspend fun getPending(): List<PendingShoppingWrite>

    @Query("SELECT COUNT(*) FROM pending_shopping_writes WHERE failed = 0")
    suspend fun countPending(): Int

    /** Everything, pending and failed, across every list — a screen filters to its own [PendingShoppingWrite.listId]. */
    @Query("SELECT * FROM pending_shopping_writes ORDER BY id ASC")
    fun observeAll(): Flow<List<PendingShoppingWrite>>

    /** A conflicted entry: kept for its list's notice, excluded from every future replay. */
    @Query("UPDATE pending_shopping_writes SET failed = 1 WHERE id = :id")
    suspend fun markFailed(id: Long)

    /** The list itself is gone, so nothing queued for it, failed or not, has anywhere to land. */
    @Query("DELETE FROM pending_shopping_writes WHERE listId = :listId")
    suspend fun deleteForList(listId: String)

    /** The user has seen this list's conflict notice — its failed entries have served their purpose. */
    @Query("DELETE FROM pending_shopping_writes WHERE failed = 1 AND listId = :listId")
    suspend fun deleteFailedForList(listId: String)
}
