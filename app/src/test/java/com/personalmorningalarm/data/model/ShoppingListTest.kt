package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.entity.PendingShoppingWrite
import com.personalmorningalarm.data.entity.ShoppingVerb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turning one shopping list's `/shopping/{listId}` array into display-ready items, mirrors RollingTodoTest. */
class ShoppingListTest {

    private fun item(text: String?, line: String? = null, ticked: Boolean? = false) =
        ShoppingItemDto(text, line, ticked)

    private fun pending(
        listId: String = "6-life/shopping/garden.md",
        verb: ShoppingVerb,
        text: String,
        line: String? = null,
        failed: Boolean = false
    ) = PendingShoppingWrite(
        listId = listId, verb = verb.name, text = text, line = line, createdAt = 0L, failed = failed
    )

    @Test
    fun `short title drops what follows the spaced dash`() {
        assertEquals("Garden Tools", ShoppingList.shortTitle("Garden Tools — Seasonal Buy List"))
        assertEquals("Alfred tech", ShoppingList.shortTitle("Alfred tech"))
        assertEquals(" — odd", ShoppingList.shortTitle(" — odd"))
    }

    @Test
    fun `name and note split at the first spaced dash`() {
        val item = ShoppingItem(task = "Hedge shears — after the spring cut — ~£40")

        assertEquals("Hedge shears", item.name)
        assertEquals("after the spring cut — ~£40", item.note)
    }

    @Test
    fun `an item with no spaced dash is all name, no note`() {
        val plain = ShoppingItem(task = "Bird feeder 2L")
        val hyphenated = ShoppingItem(task = "Seed-tray—spare lid")
        val trailing = ShoppingItem(task = "Short USB cable — ")

        assertEquals("Bird feeder 2L", plain.name)
        assertNull(plain.note)
        assertEquals("Seed-tray—spare lid", hyphenated.name)
        assertNull(hyphenated.note)
        assertEquals("Short USB cable", trailing.name)
        assertNull(trailing.note)
    }

    @Test
    fun `keeps API order`() {
        val items = ShoppingList.items(listOf(item("Hedge shears"), item("Cable pulley"), item("HDMI cable")))

        assertEquals(listOf("Hedge shears", "Cable pulley", "HDMI cable"), items.map { it.task })
    }

    @Test
    fun `wikilinks are resolved before display`() {
        val items = ShoppingList.items(listOf(item("See [[garden-guide]] §2 ~£10")))

        assertEquals("See garden-guide §2 ~£10", items.single().task)
    }

    @Test
    fun `entries with no text are dropped`() {
        val items = ShoppingList.items(listOf(item(null), item("  "), item("Hedge shears")))

        assertEquals(listOf("Hedge shears"), items.map { it.task })
    }

    @Test
    fun `an empty response produces no items`() {
        assertTrue(ShoppingList.items(emptyList()).isEmpty())
    }

    @Test
    fun `the raw line rides along untouched as the targeting key`() {
        val line = "- [ ] Hedge shears"
        assertEquals(line, ShoppingList.items(listOf(item("Hedge shears", line))).single().line)
    }

    @Test
    fun `ticked is read from the explicit flag, not the line`() {
        val items = ShoppingList.items(listOf(item("Hedge shears", "- [ ] Hedge shears", ticked = true)))

        assertTrue(items.single().done)
    }

    @Test
    fun `a null ticked flag is treated as open`() {
        val items = ShoppingList.items(listOf(item("Hedge shears", "- [ ] Hedge shears", ticked = null)))

        assertEquals(false, items.single().done)
    }

    @Test
    fun `no line means untargetable, from data cached before lines existed`() {
        val item = ShoppingList.items(listOf(item("Hedge shears", line = null))).single()

        assertNull(item.line)
    }

    // --- merged: the snapshot with this list's queued edits laid over it ---

    private val shearsLine = "- [ ] Hedge shears"
    private val snapshot = listOf(
        item("Hedge shears", line = shearsLine),
        item("Cable pulley", line = "- [ ] Cable pulley")
    )

    @Test
    fun `a queued add appends a pending item`() {
        val items = ShoppingList.merged(snapshot, listOf(pending(verb = ShoppingVerb.ADD, text = "Yoga mat")))

        assertEquals(3, items.size)
        val added = items.last()
        assertEquals("Yoga mat", added.task)
        assertTrue(added.pending)
        assertNull(added.line)
    }

    @Test
    fun `a queued tick flips its item, marked pending`() {
        val items = ShoppingList.merged(
            snapshot, listOf(pending(verb = ShoppingVerb.TICK, text = "Hedge shears", line = shearsLine))
        )

        val ticked = items.single { it.task == "Hedge shears" }
        assertTrue(ticked.done)
        assertTrue(ticked.pending)
        assertEquals(false, items.single { it.task == "Cable pulley" }.done)
    }

    @Test
    fun `a queued drop removes its item`() {
        val items = ShoppingList.merged(
            snapshot, listOf(pending(verb = ShoppingVerb.DROP, text = "Hedge shears", line = shearsLine))
        )

        assertEquals(listOf("Cable pulley"), items.map { it.task })
    }

    @Test
    fun `queued edits apply in capture order`() {
        val items = ShoppingList.merged(
            snapshot,
            listOf(
                pending(verb = ShoppingVerb.ADD, text = "Yoga mat"),
                pending(verb = ShoppingVerb.TICK, text = "Hedge shears", line = shearsLine),
                pending(verb = ShoppingVerb.DROP, text = "Cable pulley", line = "- [ ] Cable pulley")
            )
        )

        assertEquals(listOf("Hedge shears", "Yoga mat"), items.map { it.task })
        assertTrue(items.single { it.task == "Hedge shears" }.done)
    }

    @Test
    fun `failed entries are a notice, not part of the list`() {
        val items = ShoppingList.merged(
            snapshot,
            listOf(
                pending(verb = ShoppingVerb.DROP, text = "Hedge shears", line = shearsLine, failed = true),
                pending(verb = ShoppingVerb.ADD, text = "Yoga mat", failed = true)
            )
        )

        assertEquals(listOf("Hedge shears", "Cable pulley"), items.map { it.task })
    }

    @Test
    fun `an unknown queued verb is ignored by the merge`() {
        val unknown = PendingShoppingWrite(
            listId = "6-life/shopping/garden.md", verb = "TELEPORT", text = "From a newer build",
            line = null, createdAt = 0L
        )

        assertEquals(2, ShoppingList.merged(snapshot, listOf(unknown)).size)
    }

    @Test
    fun `merge does not itself filter by list — the caller must pre-filter`() {
        // ShoppingList.merged trusts its [pending] argument; a stray entry from
        // another list is applied exactly like one for this list would be. The
        // ViewModel is what filters the shared queue down to one list before this
        // is ever called.
        val items = ShoppingList.merged(
            snapshot, listOf(pending(listId = "6-life/shopping/fashion.md", verb = ShoppingVerb.ADD, text = "Scarf"))
        )

        assertEquals(3, items.size)
        assertEquals("Scarf", items.last().task)
    }

    @Test
    fun `with no snapshot at all, queued adds still make a list`() {
        val items = ShoppingList.merged(emptyList(), listOf(pending(verb = ShoppingVerb.ADD, text = "Yoga mat")))

        assertEquals(listOf("Yoga mat"), items.map { it.task })
        assertTrue(items.single().pending)
    }

    @Test
    fun `a link inside backticks survives to the renderer, in server and queued items alike`() {
        val raw = "Felt `[[not a link]]` — see [[shed-guide]]"
        val item = ShoppingList.items(listOf(ShoppingItemDto(text = raw, line = null, ticked = false))).single()

        assertEquals("Felt `[[not a link]]`", item.name)
        assertEquals("see shed-guide", item.note)
        val queued = PendingShoppingWrite(listId = "l", verb = ShoppingVerb.ADD.name, text = raw, line = null, createdAt = 0)
        assertEquals(item.task, ShoppingList.merged(emptyList(), listOf(queued)).single().task)
    }

    // --- identical rows: one queued edit, one row, the row Alfred will change ---

    private val milkLine = "- [ ] Milk"
    private val twoMilks = listOf(item("Milk", line = milkLine), item("Milk", line = milkLine))
    private fun milkEdit(verb: ShoppingVerb, line: String = milkLine) = pending(verb = verb, text = "Milk", line = line)

    @Test
    fun `one queued tick on two identical rows ticks the first alone`() {
        val items = ShoppingList.merged(twoMilks, listOf(milkEdit(ShoppingVerb.TICK)))

        assertEquals(listOf(true, false), items.map { it.done })
        assertEquals(listOf(true, false), items.map { it.pending })
    }

    @Test
    fun `two queued ticks on the same line show as two ticked rows`() {
        val items = ShoppingList.merged(twoMilks, listOf(milkEdit(ShoppingVerb.TICK), milkEdit(ShoppingVerb.TICK)))

        assertEquals(listOf(true, true), items.map { it.done })
    }

    @Test
    fun `one queued drop on two identical rows removes one`() {
        val items = ShoppingList.merged(twoMilks, listOf(milkEdit(ShoppingVerb.DROP)))

        assertEquals(listOf(milkLine), items.map { it.line })
    }

    @Test
    fun `a queued drop after a queued tick takes the row still open, as replay will`() {
        val items = ShoppingList.merged(twoMilks, listOf(milkEdit(ShoppingVerb.TICK), milkEdit(ShoppingVerb.DROP)))

        assertEquals(listOf(true), items.map { it.done })
    }

    @Test
    fun `a queued tick carries the line Alfred will hold, so dropping that row targets it`() {
        val items = ShoppingList.merged(
            twoMilks, listOf(milkEdit(ShoppingVerb.TICK), milkEdit(ShoppingVerb.DROP, line = "- [x] Milk"))
        )

        assertEquals(listOf(milkLine), items.map { it.line })
        assertEquals(listOf(false), items.map { it.done })
    }

    @Test
    fun `a queued tick with no open row left changes nothing`() {
        val items = ShoppingList.merged(
            listOf(item("Milk", line = milkLine)),
            listOf(milkEdit(ShoppingVerb.TICK), milkEdit(ShoppingVerb.TICK))
        )

        assertEquals(listOf(true), items.map { it.done })
    }
}
