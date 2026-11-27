package com.personalmorningalarm.data.model

import com.google.gson.annotations.SerializedName

/**
 * One shopping list as Alfred's discovery endpoint (`GET /shopping`) describes it —
 * enough to render the list-picker menu without fetching every list's items.
 */
data class ShoppingListSummaryDto(
    /** The vault-relative file id (e.g. "6-life/shopping/garden.md") — targets the per-list endpoints. */
    val id: String?,
    val title: String?,
    val total: Int?,
    val unticked: Int?,
    /**
     * Whose list it is, as the hub names people ("nick", "jess"). Sent with `?for=`,
     * which the app always uses; absent means Nick's, the hub's default for a list
     * written before lists had owners.
     */
    val owner: String? = null,
    /** Whether the other person sees it too. Absent means not shared. */
    val shared: Boolean? = null,
    /**
     * The list keeps its ticked (bought) items until "Clear bought": no overnight
     * sweep takes them (vault-api Session CP). Sent only when true; absent is false.
     */
    @SerializedName("keep_ticked") val keepTicked: Boolean? = null
)

/** `GET /shopping/{listId}` — one list's items, ticked included, each with its raw vault line. */
data class ShoppingListDetailDto(
    val list: ShoppingListSummaryDto?,
    val items: List<ShoppingItemDto>?
)

/**
 * One item from a shopping list. Shares [ChalkboardTaskDto]'s shape minus the date —
 * shopping items aren't dated — and [ticked] is sent explicitly rather than only
 * living in [line]'s markdown, unlike the chalkboard.
 */
data class ShoppingItemDto(
    val text: String?,
    val line: String?,
    val ticked: Boolean?
)
