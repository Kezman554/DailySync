package com.personalmorningalarm.data.model

/**
 * One shopping list as Alfred's discovery endpoint (`GET /shopping`) describes it —
 * enough to render the list-picker menu without fetching every list's items.
 */
data class ShoppingListSummaryDto(
    /** The vault-relative file id (e.g. "6-life/shopping/garden.md") — targets the per-list endpoints. */
    val id: String?,
    val title: String?,
    val total: Int?,
    val unticked: Int?
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
