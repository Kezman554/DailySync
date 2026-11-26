package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.phone.ListHome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The To-do screen's two lists as one: merged, filtered, and where an add goes. */
class TodoBoardTest {

    private fun item(task: String, added: String? = null, done: Boolean = false) =
        RollingTodoItem(task = task, date = added, addedOn = added?.let(LocalDate::parse), done = done)

    private fun TodoRow.label() = "${source.name.lowercase()}:${item.task}"

    // --- merge ---

    @Test
    fun `the two lists interleave by the day each item went on, oldest first`() {
        val family = listOf(item("Bins out", "2026-11-02"), item("Book the boiler", "2026-11-20"))
        val mine = listOf(item("Oil the gate", "2026-11-01"), item("Sort the shed", "2026-11-10"))

        val rows = TodoBoard.merge(family, mine)

        assertEquals(
            listOf("mine:Oil the gate", "family:Bins out", "mine:Sort the shed", "family:Book the boiler"),
            rows.map { it.label() }
        )
    }

    @Test
    fun `each list keeps its own order, even when its dates don't`() {
        val family = listOf(item("Later", "2026-11-20"), item("Earlier", "2026-11-02"))

        val rows = TodoBoard.merge(family, emptyList())

        assertEquals(listOf("family:Later", "family:Earlier"), rows.map { it.label() })
    }

    @Test
    fun `on the same day the family item comes first`() {
        val rows = TodoBoard.merge(listOf(item("Bins out", "2026-11-02")), listOf(item("Oil the gate", "2026-11-02")))

        assertEquals(listOf("family:Bins out", "mine:Oil the gate"), rows.map { it.label() })
    }

    @Test
    fun `an undated item, such as one waiting to sync, waits behind the dated ones`() {
        val family = listOf(item("Queued for the wall"))
        val mine = listOf(item("Oil the gate", "2026-11-01"))

        val rows = TodoBoard.merge(family, mine)

        assertEquals(listOf("mine:Oil the gate", "family:Queued for the wall"), rows.map { it.label() })
    }

    @Test
    fun `ticked items keep their place, and a list never loaded adds nothing`() {
        val mine = listOf(item("Done already", "2026-11-01", done = true), item("Still to do", "2026-11-03"))

        val rows = TodoBoard.merge(null, mine)

        assertEquals(listOf("mine:Done already", "mine:Still to do"), rows.map { it.label() })
        assertTrue(rows.first().item.done)
        assertTrue(TodoBoard.merge(null, null).isEmpty())
    }

    // --- filter ---

    private val rows = TodoBoard.merge(
        listOf(item("Bins out", "2026-11-02")),
        listOf(item("Oil the gate", "2026-11-01"))
    )

    @Test
    fun `All shows both lists, Family and Mine one each`() {
        assertEquals(listOf("mine:Oil the gate", "family:Bins out"), TodoBoard.filter(rows, TodoFilter.ALL).map { it.label() })
        assertEquals(listOf("family:Bins out"), TodoBoard.filter(rows, TodoFilter.FAMILY).map { it.label() })
        assertEquals(listOf("mine:Oil the gate"), TodoBoard.filter(rows, TodoFilter.MINE).map { it.label() })
    }

    // --- where an add goes ---

    @Test
    fun `the add picker starts on the list in view, and on Mine from All`() {
        assertEquals(TodoSource.MINE, TodoBoard.addTarget(TodoFilter.ALL))
        assertEquals(TodoSource.FAMILY, TodoBoard.addTarget(TodoFilter.FAMILY))
        assertEquals(TodoSource.MINE, TodoBoard.addTarget(TodoFilter.MINE))
    }

    @Test
    fun `the hint says the wall for Family, and for Mine where Mine lives`() {
        assertEquals(AddHint.WALL, TodoBoard.addHint(TodoSource.FAMILY, ListHome.PHONE))
        assertEquals(AddHint.WALL, TodoBoard.addHint(TodoSource.FAMILY, ListHome.HUB))
        assertEquals(AddHint.PHONE, TodoBoard.addHint(TodoSource.MINE, ListHome.PHONE))
        assertEquals(AddHint.YOUR_LIST, TodoBoard.addHint(TodoSource.MINE, ListHome.HUB))
    }

    // --- the Home tile ---

    @Test
    fun `counts are open items per list, and the preview the first open ones of both`() {
        val board = TodoBoard.merge(
            listOf(item("Bins out", "2026-11-02"), item("Swept tonight", "2026-11-03", done = true), item("Book the boiler", "2026-11-20")),
            listOf(item("Oil the gate", "2026-11-01"), item("Sort the shed", "2026-11-10"))
        )

        assertEquals(2, TodoBoard.openCount(board, TodoSource.FAMILY))
        assertEquals(2, TodoBoard.openCount(board, TodoSource.MINE))
        assertEquals(
            listOf("mine:Oil the gate", "family:Bins out", "mine:Sort the shed"),
            TodoBoard.preview(board).map { it.label() }
        )
    }

    // --- the work list (Nick's phone) ---

    @Test
    fun `the work list interleaves with the other two by date, after them on the same day`() {
        val rows = TodoBoard.merge(
            listOf(item("Bins out", "2026-11-02")),
            listOf(item("Oil the gate", "2026-11-02")),
            listOf(item("Release notes", "2026-11-01"), item("Rota review", "2026-11-02"), item("Queued for work"))
        )

        assertEquals(
            listOf("work:Release notes", "family:Bins out", "mine:Oil the gate", "work:Rota review", "work:Queued for work"),
            rows.map { it.label() }
        )
    }

    @Test
    fun `with no work list the merge is the two lists as before`() {
        val family = listOf(item("Bins out", "2026-11-02"), item("Undated"))
        val mine = listOf(item("Oil the gate", "2026-11-01"), item("Also undated"))

        assertEquals(TodoBoard.merge(family, mine, null), TodoBoard.merge(family, mine))
        assertEquals(
            listOf("mine:Oil the gate", "family:Bins out", "family:Undated", "mine:Also undated"),
            TodoBoard.merge(family, mine).map { it.label() }
        )
    }

    @Test
    fun `Work shows the work list alone, All shows it with the others, and adds from Work go to it`() {
        val board = TodoBoard.merge(
            listOf(item("Bins out", "2026-11-02")),
            listOf(item("Oil the gate", "2026-11-01")),
            listOf(item("Release notes", "2026-11-03"))
        )

        assertEquals(listOf("work:Release notes"), TodoBoard.filter(board, TodoFilter.WORK).map { it.label() })
        assertEquals(3, TodoBoard.filter(board, TodoFilter.ALL).size)
        assertEquals(listOf("mine:Oil the gate"), TodoBoard.filter(board, TodoFilter.MINE).map { it.label() })
        assertEquals(TodoSource.WORK, TodoBoard.addTarget(TodoFilter.WORK))
        assertEquals(AddHint.WORK, TodoBoard.addHint(TodoSource.WORK, ListHome.HUB))
        assertEquals(AddHint.WORK, TodoBoard.addHint(TodoSource.WORK, ListHome.PHONE))
        assertEquals(1, TodoBoard.openCount(board, TodoSource.WORK))
    }

    @Test
    fun `a saved filter name round-trips, and one this build doesn't know is null`() {
        TodoFilter.entries.forEach { assertEquals(it, TodoFilter.fromName(it.name)) }
        assertEquals(null, TodoFilter.fromName("SOMEDAY"))
        assertEquals(null, TodoFilter.fromName(null))
    }
}
