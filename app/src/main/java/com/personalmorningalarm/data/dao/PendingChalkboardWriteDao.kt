package com.personalmorningalarm.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.personalmorningalarm.data.entity.PendingChalkboardWrite
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingChalkboardWriteDao {

    @Insert
    suspend fun insert(write: PendingChalkboardWrite): Long

    @Delete
    suspend fun delete(write: PendingChalkboardWrite)

    /** Replay order: id is AUTOINCREMENT, so this is insertion (FIFO) order. */
    @Query("SELECT * FROM pending_chalkboard_writes WHERE failed = 0 ORDER BY id ASC")
    suspend fun getPending(): List<PendingChalkboardWrite>

    @Query("SELECT COUNT(*) FROM pending_chalkboard_writes WHERE failed = 0")
    suspend fun countPending(): Int

    /** Everything, pending and failed, for the screen's markers and notices. */
    @Query("SELECT * FROM pending_chalkboard_writes ORDER BY id ASC")
    fun observeAll(): Flow<List<PendingChalkboardWrite>>

    /** A conflicted entry: kept for the notice, excluded from every future replay. */
    @Query("UPDATE pending_chalkboard_writes SET failed = 1 WHERE id = :id")
    suspend fun markFailed(id: Long)

    /** The user has seen the conflict notice — the failed entries have served their purpose. */
    @Query("DELETE FROM pending_chalkboard_writes WHERE failed = 1")
    suspend fun deleteFailed()

    /** Gives an ADD that has none (queued by an older build) its key, kept for every later attempt. */
    @Query("UPDATE pending_chalkboard_writes SET idempotencyKey = :key WHERE id = :id")
    suspend fun setKey(id: Long, key: String)
}
