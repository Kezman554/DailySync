package com.personalmorningalarm.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One shopping-list write made while Alfred was unreachable, queued until it can be
 * delivered. Mirrors [PendingChalkboardWrite], with a [listId] added — the queue is
 * one FIFO outbox shared across every shopping list, not one per list, so [id]
 * (AUTOINCREMENT) is still the global replay order.
 */
@Entity(tableName = "pending_shopping_writes")
data class PendingShoppingWrite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The vault-relative list id this write targets, e.g. "6-life/shopping/garden.md". */
    val listId: String,
    /** A [ShoppingVerb] name. Stored as text; unknown names are failed, not fatal. */
    val verb: String,
    /** The item text: what an ADD sends, and what the conflict notice shows for tick/drop. */
    val text: String,
    /** The targeting line for TICK/DROP, taken from the snapshot the user acted on. Null for ADD. */
    val line: String?,
    val createdAt: Long,
    val failed: Boolean = false
)

/** The three shopping-list writes the queue can hold. Create-list is never queued — it's online-only. */
enum class ShoppingVerb {
    ADD,
    TICK,
    DROP;

    companion object {
        /** Null for a name this build doesn't know — the flush fails that entry rather than crash. */
        fun fromName(name: String): ShoppingVerb? = entries.firstOrNull { it.name == name }
    }
}
