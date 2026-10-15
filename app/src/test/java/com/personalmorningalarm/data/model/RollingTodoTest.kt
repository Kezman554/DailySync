package com.personalmorningalarm.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import com.personalmorningalarm.data.entity.ChalkboardVerb
import com.personalmorningalarm.data.entity.PendingChalkboardWrite

/** Turning Alfred's /chalkboard array into display-ready items. */
class RollingTodoTest {

    private fun task(task: String?, date: String? = null, line: String? = null) =
        ChalkboardTaskDto(task, date, line)

    private fun pending(
        verb: ChalkboardVerb,
        text: String,
        line: String? = null,
        failed: Boolean = false
    ) = PendingChalkboardWrite(
        verb = verb.name, text = text, line = line, createdAt = 0L, failed = failed
    )

    @Test
    fun `oldest open age counts from the earliest dated item still to do`() {
        val items = RollingTodo.items(
            listOf(
                task("Ticked long ago", "2026-01-01", line = "- [x] Ticked long ago"),
                task("Oldest open", "2026-04-08"),
                task("Newer", "2026-07-13"),
                task("Undated")
            )
        )
        assertEquals(190L, RollingTodo.oldestOpenAgeDays(items, java.time.LocalDate.of(2026, 10, 15)))
        assertEquals(java.time.LocalDate.of(2026, 4, 8), items[1].addedOn)
    }

    @Test
    fun `no dated open item gives no oldest age`() {
        val items = RollingTodo.items(listOf(task("Undated"), task("Odd", "sometime")))
        assertEquals(null, RollingTodo.oldestOpenAgeDays(items, java.time.LocalDate.of(2026, 10, 15)))
    }

    @Test
    fun `keeps API order`() {
        val items = RollingTodo.items(
            listOf(task("Plant the bamboo"), task("Cancel Beer52"), task("Trim front hedges"))
        )

        assertEquals(
            listOf("Plant the bamboo", "Cancel Beer52", "Trim front hedges"),
            items.map { it.task }
        )
    }

    @Test
    fun `an ISO date is formatted for the secondary line`() {
        val items = RollingTodo.items(listOf(task("Plant the bamboo", "2026-07-05")))

        assertEquals("5 Jul 2026", items.single().date)
    }

    @Test
    fun `a missing date gives no line rather than the string null`() {
        val items = RollingTodo.items(listOf(task("Sort belt bag", null)))

        assertNull(items.single().date)
    }

    @Test
    fun `a blank date gives no line either`() {
        assertNull(RollingTodo.items(listOf(task("Sort belt bag", "   "))).single().date)
        assertNull(RollingTodo.items(listOf(task("Sort belt bag", ""))).single().date)
    }

    @Test
    fun `an unparseable date is shown as sent rather than dropped`() {
        val items = RollingTodo.items(listOf(task("Sort tin cupboard", "last Tuesday")))

        assertEquals("last Tuesday", items.single().date)
    }

    @Test
    fun `wikilinks are resolved before display`() {
        val items = RollingTodo.items(listOf(task("See [[pi-admin-guide]] §9c", "2026-07-13")))

        assertEquals("See pi-admin-guide §9c", items.single().task)
    }

    @Test
    fun `entries with no task text are dropped`() {
        val items = RollingTodo.items(listOf(task(null, "2026-07-13"), task("  "), task("Fix fence")))

        assertEquals(listOf("Fix fence"), items.map { it.task })
    }

    @Test
    fun `an empty response produces no items`() {
        assertTrue(RollingTodo.items(emptyList()).isEmpty())
    }

    // --- the raw line: targeting key for writes, and where done state lives ---

    @Test
    fun `the raw line rides along untouched as the targeting key`() {
        val line = "- [ ] Fix fence (2026-07-05)"
        val items = RollingTodo.items(listOf(task("Fix fence", "2026-07-05", line)))

        assertEquals(line, items.single().line)
    }

    @Test
    fun `a ticked line marks the item done`() {
        val items = RollingTodo.items(
            listOf(
                task("Fix fence", line = "- [x] Fix fence (2026-07-05)"),
                task("Shout louder", line = "- [X] Shout louder")
            )
        )

        assertTrue(items.all { it.done })
    }

    @Test
    fun `an unticked line leaves the item open`() {
        val items = RollingTodo.items(listOf(task("Fix fence", line = "- [ ] Fix fence")))

        assertEquals(false, items.single().done)
    }

    @Test
    fun `no line means open and untargetable, from data cached before lines existed`() {
        val item = RollingTodo.items(listOf(task("Fix fence"))).single()

        assertNull(item.line)
        assertEquals(false, item.done)
    }

    // --- merged: the snapshot with the offline queue's edits laid over it ---

    private val fenceLine = "- [ ] Fix fence"
    private val snapshot = listOf(
        task("Fix fence", line = fenceLine),
        task("Plant the bamboo", line = "- [ ] Plant the bamboo")
    )

    @Test
    fun `a queued add appends a pending item`() {
        val items = RollingTodo.merged(snapshot, listOf(pending(ChalkboardVerb.ADD, "Buy stamps")))

        assertEquals(3, items.size)
        val added = items.last()
        assertEquals("Buy stamps", added.task)
        assertTrue(added.pending)
        assertNull(added.line)
    }

    @Test
    fun `a queued tick flips its item, marked pending`() {
        val items = RollingTodo.merged(
            snapshot, listOf(pending(ChalkboardVerb.TICK, "Fix fence", fenceLine))
        )

        val ticked = items.single { it.task == "Fix fence" }
        assertTrue(ticked.done)
        assertTrue(ticked.pending)
        assertEquals(false, items.single { it.task == "Plant the bamboo" }.done)
    }

    @Test
    fun `a queued drop removes its item`() {
        val items = RollingTodo.merged(
            snapshot, listOf(pending(ChalkboardVerb.DROP, "Fix fence", fenceLine))
        )

        assertEquals(listOf("Plant the bamboo"), items.map { it.task })
    }

    @Test
    fun `queued edits apply in capture order`() {
        val items = RollingTodo.merged(
            snapshot,
            listOf(
                pending(ChalkboardVerb.ADD, "Buy stamps"),
                pending(ChalkboardVerb.TICK, "Fix fence", fenceLine),
                pending(ChalkboardVerb.DROP, "Plant the bamboo", "- [ ] Plant the bamboo")
            )
        )

        assertEquals(listOf("Fix fence", "Buy stamps"), items.map { it.task })
        assertTrue(items.single { it.task == "Fix fence" }.done)
    }

    @Test
    fun `failed entries are a notice, not part of the list`() {
        val items = RollingTodo.merged(
            snapshot,
            listOf(
                pending(ChalkboardVerb.DROP, "Fix fence", fenceLine, failed = true),
                pending(ChalkboardVerb.ADD, "Buy stamps", failed = true)
            )
        )

        assertEquals(listOf("Fix fence", "Plant the bamboo"), items.map { it.task })
    }

    @Test
    fun `an unknown queued verb is ignored by the merge`() {
        val unknown = PendingChalkboardWrite(
            verb = "TELEPORT", text = "From a newer build", line = null, createdAt = 0L
        )

        assertEquals(2, RollingTodo.merged(snapshot, listOf(unknown)).size)
    }

    @Test
    fun `with no snapshot at all, queued adds still make a list`() {
        val items = RollingTodo.merged(emptyList(), listOf(pending(ChalkboardVerb.ADD, "Buy stamps")))

        assertEquals(listOf("Buy stamps"), items.map { it.task })
        assertTrue(items.single().pending)
    }
}
