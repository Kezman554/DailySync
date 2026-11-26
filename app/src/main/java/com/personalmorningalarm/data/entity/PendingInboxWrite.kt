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
 *
 * [idempotencyKey] is made when the user captures it and sent unchanged on every
 * attempt, so a resend after a lost answer makes no second file.
 */
@Entity(tableName = "pending_inbox_writes")
data class PendingInboxWrite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The capture text, exactly as it will be POSTed. */
    val text: String,
    val createdAt: Long,
    val failed: Boolean = false,
    /** Its Idempotency-Key ([com.personalmorningalarm.data.remote.AddKey]). */
    val idempotencyKey: String
)
