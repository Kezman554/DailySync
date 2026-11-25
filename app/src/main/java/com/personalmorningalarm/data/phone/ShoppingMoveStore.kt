package com.personalmorningalarm.data.phone

/** One item as it crosses between the phone and the hub: its text and whether it's ticked. */
data class MovingItem(val text: String, val ticked: Boolean)

/**
 * The phone's half of moving a shopping list to or from the hub: each step durable
 * before the next network call, so a move cut short is finished or undone later
 * rather than left half done. [PhoneListStore] is the real one; a test can wrap it to
 * fail between steps.
 *
 * Every call runs to completion once started, and throws if the database does.
 */
interface ShoppingMoveStore {

    /** A phone shopping list as a share would upload it. */
    data class Snapshot(val title: String, val items: List<MovingItem>)

    /** [listId]'s title and the items still showing, or null if there's no such list. */
    suspend fun shareSnapshot(listId: String): Snapshot?

    suspend fun move(phoneListId: String): ListMove?

    /** The pull-back under way from hub list [hubId], if any. */
    suspend fun pullBackFrom(hubId: String): ListMove?

    /** Every move under way, oldest first: what a resume works through. */
    suspend fun moves(): List<ListMove>

    /** Records a share of [listId] about to upload. From here the list takes no edits. */
    suspend fun beginShare(listId: String, title: String, hubIdsBefore: List<String>)

    /** The upload is in: the hub's copy is [hubId]. From here the phone copy is hidden. */
    suspend fun recordUpload(listId: String, hubId: String)

    /** Deletes the phone copy and its move record together. */
    suspend fun finishShare(listId: String)

    /** Drops a move that didn't happen; the phone list is as it was, and editable again. */
    suspend fun cancelMove(listId: String)

    /**
     * Makes a hidden phone list called [title] holding [items] (the hub list as last
     * read), recorded as being pulled back from [hubId]. Returns the new list's id.
     */
    suspend fun beginPullBack(title: String, hubId: String, items: List<MovingItem>): String

    /** The hub's copy is gone: keep [items] (the hub's answer), or the snapshot if null, and show the list. */
    suspend fun finishPullBack(listId: String, items: List<MovingItem>?)

    /** The pull-back didn't happen: the snapshot list goes, and the hub's copy is still the list. */
    suspend fun abandonPullBack(listId: String)
}
