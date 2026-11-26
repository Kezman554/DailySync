package com.personalmorningalarm.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.personalmorningalarm.data.entity.PendingInboxWrite
import kotlinx.coroutines.flow.Flow

/** Mirrors [PendingShoppingWriteDao], minus the per-list scoping the inbox doesn't have. */
@Dao
interface PendingInboxWriteDao {

    @Insert
    suspend fun insert(write: PendingInboxWrite): Long

    @Delete
    suspend fun delete(write: PendingInboxWrite)

    /** Replay order: id is AUTOINCREMENT, so this is insertion (FIFO) order. */
    @Query("SELECT * FROM pending_inbox_writes WHERE failed = 0 ORDER BY id ASC")
    suspend fun getPending(): List<PendingInboxWrite>

    @Query("SELECT COUNT(*) FROM pending_inbox_writes WHERE failed = 0")
    suspend fun countPending(): Int

    /** Everything, pending and failed — the screen splits them for its notices. */
    @Query("DELETE FROM pending_inbox_writes WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Records that [id] is about to be sent ([at]), or (null) that it never got out. */
    @Query("UPDATE pending_inbox_writes SET attemptedAt = :at WHERE id = :id")
    suspend fun setAttempted(id: Long, at: Long?)

    @Query("SELECT * FROM pending_inbox_writes ORDER BY id ASC")
    fun observeAll(): Flow<List<PendingInboxWrite>>

    /** A capture Alfred refused: kept for the notice, excluded from every future replay. */
    @Query("UPDATE pending_inbox_writes SET failed = 1 WHERE id = :id")
    suspend fun markFailed(id: Long)

    /** The user has seen the notice — the refused captures have served their purpose. */
    @Query("DELETE FROM pending_inbox_writes WHERE failed = 1")
    suspend fun deleteFailed()
}
