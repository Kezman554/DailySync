package com.personalmorningalarm.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One inbox capture made while Alfred was unreachable, queued until it can be
 * delivered. The third table in the [PendingChalkboardWrite]/[PendingShoppingWrite]
 * family, and the simplest of them: the inbox takes exactly one kind of write
 * (capture this text), so there's no verb and no targeting line — a capture creates
 * a new file rather than editing an existing one, which is also why it can never go
 * stale the way a tick/drop can.
 *
 * [failed] therefore means something narrower here than it does in the other two
 * queues: not "the vault moved on" but "Alfred refused this text outright" (a 4xx
 * that isn't a transport failure). It exists so one unacceptable capture can't stall
 * the FIFO behind it forever.
 */
@Entity(tableName = "pending_inbox_writes")
data class PendingInboxWrite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The capture text, exactly as it will be POSTed. */
    val text: String,
    val createdAt: Long,
    val failed: Boolean = false,
    /**
     * When this capture was last sent with no confirmed answer, or null if it has
     * never been known to leave the phone. Set before each send and cleared again if
     * the send never got a connection, so a process death mid-send leaves it set. A
     * set value means it may already be on the hub: InboxSync looks before resending.
     */
    val attemptedAt: Long? = null
)
