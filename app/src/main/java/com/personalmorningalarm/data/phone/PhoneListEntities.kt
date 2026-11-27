package com.personalmorningalarm.data.phone

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Where a list or note lives. A HUB list is Alfred's: the phone only caches it and
 * queues writes for it. A PHONE list is kept here, on the device, and nothing about
 * it ever goes to Alfred or through the offline queue.
 */
enum class ListHome {
    HUB, PHONE;

    companion object {
        /** Unknown or missing reads as HUB: that's where every list lived before this existed. */
        fun fromName(name: String?): ListHome = entries.firstOrNull { it.name == name } ?: HUB
    }
}

/** What kind of list a [PhoneList] is. The to-do is a single list with a fixed id. */
enum class PhoneListKind { SHOPPING, TODO }

/**
 * A list kept on the phone. Every row in this table lives on the phone; the hub's
 * lists are never stored here, only in Alfred's response cache.
 */
@Entity(tableName = "phone_lists")
data class PhoneList(
    /** A UUID, or [PhoneListStore.TODO_LIST_ID] for the to-do. Never changes once made. */
    @PrimaryKey val id: String,
    /** A [PhoneListKind] name. */
    val kind: String,
    val title: String,
    val createdAt: Long,
    /**
     * Ticked items stay, under Bought, until "Clear bought": the overnight clear
     * passes this list by (BJ). Kept on the list itself, as vault-api keeps a hub
     * list's `keep_ticked`. Added in v3; every list before is false.
     */
    @ColumnInfo(defaultValue = "0") val keepTicked: Boolean = false
)

/**
 * One item on a [PhoneList]. Items are targeted by [id], not by their text, so two
 * rows with the same text stay two rows and a tick lands on the one tapped.
 */
@Entity(
    tableName = "phone_list_items",
    foreignKeys = [
        ForeignKey(
            entity = PhoneList::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("listId")]
)
data class PhoneListItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: String,
    val text: String,
    /** The day it went on the list, as an epoch day: the to-do's date and "oldest" count. */
    @ColumnInfo(name = "addedOn") val addedOnEpochDay: Long,
    /**
     * The sweep day it was ticked in, as an epoch day; null while it's still to do.
     * A sweep day starts at 03:07 ([PhoneListStore.sweepDayOf]), so a ticked item
     * stays on the list, struck through, until the next 03:07 and is gone after: the
     * hub's overnight sweep, worked out on read. (Rows ticked before that rule
     * carry the calendar day, which at worst clears them at 03:07 rather than midnight.)
     */
    @ColumnInfo(name = "tickedOn") val tickedOnEpochDay: Long? = null
) {
    val ticked: Boolean get() = tickedOnEpochDay != null
}

/** A note kept on the phone: plain text, exactly as typed. */
@Entity(tableName = "phone_notes")
data class PhoneNote(
    /** Made the same way as a hub note's id ([com.personalmorningalarm.data.model.Notes.newId]). */
    @PrimaryKey val id: String,
    val text: String,
    val updatedAt: Long
)

/** One phone list as the Shopping menu needs it: its counts, no items. */
data class PhoneListSummary(
    val id: String,
    val title: String,
    val total: Int,
    val left: Int
)

/** Which way a [ListMove] is going. */
enum class MoveDirection {
    /** A phone list going to the hub as a shared list (upload, then the phone copy is deleted). */
    SHARE,
    /** A hub list coming back to the phone (pull-back, which deletes the hub copy). */
    PULL_BACK
}

/**
 * A list part-way between the phone and the hub. Written before anything leaves the
 * phone and deleted only once the move is complete, so a move cut short (no network,
 * the app killed) is finished or undone next time rather than half done.
 *
 * While a row exists its phone list takes no edits, so the two copies can't drift. The
 * phone list is hidden from the menu once the hub has it ([hubId] set on a share) and
 * throughout a pull-back (the hub's copy is still the one showing).
 */
@Entity(tableName = "list_moves")
data class ListMove(
    /** The phone list moving: the one being shared, or the one a pull-back is filling. */
    @PrimaryKey val phoneListId: String,
    /** A [MoveDirection] name. */
    val direction: String,
    val title: String,
    /**
     * SHARE: the hub id the upload answered with, once known; null until then.
     * PULL_BACK: the hub list being pulled back.
     */
    val hubId: String?,
    /**
     * SHARE only: the hub's list ids just before the upload, one per line. If the
     * upload's answer is lost, a new list of ours with this title that isn't among
     * them is the one it made, so the upload is never repeated into a duplicate.
     */
    val hubIdsBefore: String = "",
    val startedAt: Long
)
