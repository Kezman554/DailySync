package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.entity.PendingShoppingWrite
import com.personalmorningalarm.data.entity.ShoppingVerb
import com.personalmorningalarm.util.VaultText

/** One shopping list item, cleaned up and ready to render. Mirrors [RollingTodoItem]. */
data class ShoppingItem(
    /** Wikilinks resolved; still carries emphasis markers and any "~£cost" fragment for the renderer. */
    val task: String,
    /** Raw vault line — the targeting key for tick/drop. Null on pre-line cached data. */
    val line: String? = null,
    val done: Boolean = false,
    /** Waiting in the offline queue — captured on the phone, not yet in the vault. */
    val pending: Boolean = false
) {
    /** The item itself: everything before the first " — " (all of it if there's none). */
    val name: String get() = task.substringBefore(NOTE_SEPARATOR).trim()

    /** What follows the first " — " (why, when, a rough price), or null if nothing does. */
    val note: String? get() = task.substringAfter(NOTE_SEPARATOR, "").trim().ifEmpty { null }

    private companion object {
        const val NOTE_SEPARATOR = " — "
    }
}

/**
 * Turns one shopping list's `/shopping/{listId}` items into display-ready rows, and
 * layers the offline queue's edits on top — the same shape as [RollingTodo], scoped
 * to a single list instead of the one chalkboard.
 */
object ShoppingList {

    /**
     * A list's title as a tile or header shows it: the part before " — ", so
     * "Fitness Equipment — Rolling Buy List" reads "Fitness Equipment".
     */
    fun shortTitle(title: String): String = title.substringBefore(" — ").trim().ifEmpty { title }

    fun items(items: List<ShoppingItemDto>): List<ShoppingItem> =
        items
            .filter { !it.text.isNullOrBlank() }
            .map {
                ShoppingItem(
                    task = VaultText.stripWikiLinks(it.text!!.trim()),
                    line = it.line,
                    done = it.ticked == true
                )
            }

    /**
     * The list as the user should see it: the last-known server list with their
     * queued edits (for this list only) laid over the top. [pending] is expected to
     * already be filtered to the list in question — the queue is shared across
     * every list, and merging someone else's edits in here would show them on the
     * wrong screen.
     */
    fun merged(items: List<ShoppingItemDto>, pending: List<PendingShoppingWrite>): List<ShoppingItem> {
        var result = ShoppingList.items(items)
        pending.filterNot { it.failed }.forEach { write ->
            result = when (ShoppingVerb.fromName(write.verb)) {
                ShoppingVerb.ADD -> result + ShoppingItem(
                    task = VaultText.stripWikiLinks(write.text),
                    line = null,
                    pending = true
                )
                ShoppingVerb.TICK -> result.map {
                    if (it.line == write.line) it.copy(done = true, pending = true) else it
                }
                ShoppingVerb.DROP -> result.filterNot { it.line == write.line }
                null -> result
            }
        }
        return result
    }
}
