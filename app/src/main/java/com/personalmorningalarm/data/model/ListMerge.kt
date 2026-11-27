package com.personalmorningalarm.data.model

import com.personalmorningalarm.util.VaultText
import java.util.Locale

/**
 * "Copy into…": the merge rule, written once. [plan] says what to do to your list;
 * the Pi's lists ([com.personalmorningalarm.data.remote.ShoppingCopy]) and the
 * phone's ([com.personalmorningalarm.data.phone.PhoneListStore.mergeInto]) each
 * carry it out. A copy into a new list is the same rule into an empty one.
 *
 * The rule (2-projects/dailysync/jess-dailysync.md, "Private copies and keeping
 * ticked items"):
 *  - an item is matched on its name ([key]): the part before " — ", ignoring case
 *    and extra spaces;
 *  - only items you don't have are added. One they've ticked arrives ticked;
 *  - one they've ticked that's on your list unticked is ticked there (a tick means
 *    bought);
 *  - nothing on your list is ever unticked, deleted or renamed. Items they've since
 *    deleted don't come into it at all: the plan is built from what they have, and
 *    only ever adds to or ticks what you have.
 *
 * Planned from both lists as they are now, so running it again after a merge that
 * stopped part-way does only what's left: what landed is "already there".
 */
object ListMerge {

    /** One item on the list being copied. */
    data class Theirs(val text: String, val ticked: Boolean)

    /** One item already on your list. [ref] is how its store targets it: a vault line, a row id. */
    data class Mine<R>(val ref: R, val text: String, val ticked: Boolean)

    /**
     * What happened, for the one line after a copy. Each of their items (counted once
     * per name) is in exactly one: added to buy, already bought by them (added ticked,
     * or ticked on yours), or already there (nothing to do).
     */
    data class Counts(val added: Int, val alreadyThere: Int, val alreadyBought: Int)

    data class Plan<R>(
        /** New items, in their list's order, each ticked if they'd bought it. Their text, unchanged. */
        val adds: List<Theirs>,
        /** Your items to tick: on your list unticked, bought on theirs. */
        val ticks: List<R>,
        val counts: Counts
    )

    /**
     * What an item is matched on: its name (the part before " — ", links resolved),
     * spaces collapsed, case ignored. The whole line when there's no name before the
     * dash, so " — note" still matches only itself.
     */
    fun key(text: String): String {
        val resolved = VaultText.resolveLinks(text)
        val name = resolved.substringBefore(NOTE_SEPARATOR).ifBlank { resolved }
        return name.trim().replace(SPACES, " ").lowercase(Locale.ROOT)
    }

    fun <R> plan(theirs: List<Theirs>, mine: List<Mine<R>>): Plan<R> {
        // Their items by name, in order. Two of the same name are one item: bought
        // only if every copy is ticked (an unticked one means they still need it).
        val groups = LinkedHashMap<String, MutableList<Theirs>>()
        theirs.filter { it.text.isNotBlank() }.forEach { groups.getOrPut(key(it.text)) { mutableListOf() } += it }
        val mineByKey = mine.groupBy { key(it.text) }

        val adds = mutableListOf<Theirs>()
        val ticks = mutableListOf<R>()
        var added = 0
        var alreadyThere = 0
        var alreadyBought = 0
        for ((name, copies) in groups) {
            val bought = copies.all { it.ticked }
            val existing = mineByKey[name]
            when {
                existing == null -> {
                    val text = (copies.firstOrNull { !it.ticked } ?: copies.first()).text
                    adds += Theirs(text, ticked = bought)
                    if (bought) alreadyBought++ else added++
                }
                // Yours already ticked, any copy of it: bought already, nothing to do.
                bought && existing.none { it.ticked } -> {
                    ticks += existing.first().ref
                    alreadyBought++
                }
                else -> alreadyThere++
            }
        }
        return Plan(adds, ticks, Counts(added, alreadyThere, alreadyBought))
    }

    private const val NOTE_SEPARATOR = " — "
    private val SPACES = Regex("""\s+""")
}
