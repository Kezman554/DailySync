package com.personalmorningalarm.data.model

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/**
 * One item on KitchenSync's grocery list, as `GET /api/shopping-list` sends it
 * (KitchenSync's ShoppingListItemResponse, a contract that only ever gains fields).
 * [source] is "auto" (from the meal plan or an out-of-stock staple) or "manual".
 */
data class GroceryItemDto(
    val id: Long?,
    @SerializedName("item_name") val itemName: String?,
    val quantity: Double?,
    val unit: String?,
    val source: String? = null
)

/**
 * Groceries: KitchenSync's grocery list (the supermarket list: meal-plan items,
 * staples that are out, anything added by hand) as one more list in the Shopping
 * tile. Here it's put in the shapes every other list has ([ShoppingListSummaryDto],
 * [ShoppingItemDto]), so the screens show it the way they show any list.
 *
 * It's a family list, so it has no owner, no sharing, no Copy into and no Keep
 * ticked: those are the vault's. A tick doesn't leave it struck through, either:
 * KitchenSync takes the item off the list as it puts it into stock or its "to put
 * away" tray.
 *
 * An item is targeted by its KitchenSync id, carried in [ShoppingItemDto.line]
 * ([lineOf]) where a vault item carries its raw line.
 */
object Groceries {

    /** The list's id among the others. Not a vault path, so it can't collide with one. */
    const val LIST_ID = "kitchensync/groceries"

    const val TITLE = "Groceries"

    private const val LINE_PREFIX = "kitchensync:item/"

    fun isGroceries(listId: String?): Boolean = listId == LIST_ID

    fun lineOf(itemId: Long): String = "$LINE_PREFIX$itemId"

    /** The KitchenSync id a [lineOf] line targets, or null if it isn't one. */
    fun idOf(line: String): Long? = line.removePrefix(LINE_PREFIX).takeIf { line.startsWith(LINE_PREFIX) }?.toLongOrNull()

    /**
     * How an item reads: its name, and after " — " (the list's note separator) how
     * much, unless it's simply one: "Milk — 2 l", "Eggs — 12", "Bread".
     */
    fun text(item: GroceryItemDto): String? {
        val name = item.itemName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val unit = item.unit?.trim()?.takeIf { it.isNotEmpty() }
        val quantity = item.quantity?.let(::number)
        val amount = when {
            unit != null -> listOfNotNull(quantity, unit).joinToString(" ")
            quantity != null && quantity != "1" -> quantity
            else -> null
        }
        return if (amount == null) name else "$name — $amount"
    }

    /** The list's items as every list's are: still to buy, each targeted by its id. */
    fun items(list: List<GroceryItemDto>): List<ShoppingItemDto> = list.mapNotNull { item ->
        val id = item.id ?: return@mapNotNull null
        text(item)?.let { ShoppingItemDto(text = it, line = lineOf(id), ticked = false) }
    }

    /** The list as the Shopping menu and Home tile count it. A tick removes, so everything is left. */
    fun summary(list: List<GroceryItemDto>): ShoppingListSummaryDto {
        val count = items(list).size
        return ShoppingListSummaryDto(id = LIST_ID, title = TITLE, total = count, unticked = count)
    }

    fun detail(list: List<GroceryItemDto>): ShoppingListDetailDto =
        ShoppingListDetailDto(list = summary(list), items = items(list))

    /** 2.0 -> "2", 0.5 -> "0.5", without float noise. */
    private fun number(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
