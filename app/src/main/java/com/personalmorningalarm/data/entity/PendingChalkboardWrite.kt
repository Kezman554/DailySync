package com.personalmorningalarm.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One chalkboard write (Nick's rolling to-do or the family chalkboard) made while
 * Alfred was unreachable, queued until it can be delivered. Room-backed so the queue survives restarts and reboots — offline
 * capture is the whole point, and losing an entry to a process death would make
 * the optimistic display a lie.
 *
 * FIFO by [id] (AUTOINCREMENT, so insertion order is replay order). A [failed]
 * entry hit a stale targeting line during a flush; it is kept — never retried —
 * so the screen can tell the user what didn't land, then deleted on dismiss.
 */
@Entity(tableName = "pending_chalkboard_writes")
data class PendingChalkboardWrite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** A [ChalkboardVerb] name. Stored as text; unknown names are failed, not fatal. */
    val verb: String,
    /** The task text: what an ADD sends, and what the conflict notice shows for tick/drop. */
    val text: String,
    /** The targeting line for TICK/DROP, taken from the snapshot the user acted on. Null for ADD. */
    val line: String?,
    val createdAt: Long,
    val failed: Boolean = false,
    /**
     * Which chalkboard it goes to: a [com.personalmorningalarm.data.model.ChalkboardList]
     * wire name. Writes queued before there were two lists were all Nick's, hence the default.
     */
    @ColumnInfo(defaultValue = "nick") val list: String = "nick"
)

/** The three chalkboard writes the queue can hold. */
enum class ChalkboardVerb {
    ADD,
    TICK,
    DROP;

    companion object {
        /** Null for a name this build doesn't know — the flush fails that entry rather than crash. */
        fun fromName(name: String): ChalkboardVerb? = entries.firstOrNull { it.name == name }
    }
}
