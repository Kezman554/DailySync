package com.personalmorningalarm.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.personalmorningalarm.data.entity.PendingNoteWrite
import kotlinx.coroutines.flow.Flow

/** The note queue: at most one row per note, enforced by the unique index on noteId. */
@Dao
interface PendingNoteWriteDao {

    /** Replaces whatever was queued for this note, failed or not, under a new id. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(write: PendingNoteWrite): Long

    /** By primary key, so a row replaced during its own replay stays in the queue. */
    @Delete
    suspend fun delete(write: PendingNoteWrite)

    /** The oldest unsent write after [afterId]. One at a time, so replay never sends a replaced write. */
    @Query("SELECT * FROM pending_note_writes WHERE failed = 0 AND id > :afterId ORDER BY id ASC LIMIT 1")
    suspend fun nextPending(afterId: Long): PendingNoteWrite?

    @Query("SELECT COUNT(*) FROM pending_note_writes WHERE failed = 0")
    suspend fun countPending(): Int

    @Query("SELECT * FROM pending_note_writes WHERE noteId = :noteId")
    suspend fun find(noteId: String): PendingNoteWrite?

    /** Everything, refused entries included: the list shows them. */
    @Query("SELECT * FROM pending_note_writes ORDER BY id ASC")
    fun observeAll(): Flow<List<PendingNoteWrite>>

    /** Refused by Alfred: kept for its text, excluded from every future replay. */
    @Query("UPDATE pending_note_writes SET failed = 1 WHERE id = :id")
    suspend fun markFailed(id: Long)
}
