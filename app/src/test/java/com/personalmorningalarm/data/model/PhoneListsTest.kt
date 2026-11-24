package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.phone.ListHome
import com.personalmorningalarm.data.phone.PhoneListItem
import com.personalmorningalarm.data.phone.PhoneListSummary
import com.personalmorningalarm.data.phone.PhoneNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Phone rows in the shapes the hub's screens already render. */
class PhoneListsTest {

    private val day = LocalDate.of(2026, 3, 14)

    @Test
    fun `shopping items are targeted by their row id, with no vault line`() {
        val items = PhoneLists.shoppingItems(
            listOf(
                PhoneListItem(id = 7, listId = "l", text = "Gas — the small one", addedOnEpochDay = day.toEpochDay()),
                PhoneListItem(id = 8, listId = "l", text = "Rope", addedOnEpochDay = 0, tickedOnEpochDay = 1)
            )
        )

        assertEquals(listOf(7L, 8L), items.map { it.localId })
        assertEquals(listOf(null, null), items.map { it.line })
        assertEquals(listOf(false, true), items.map { it.done })
        assertEquals("Gas", items[0].name)
        assertEquals("the small one", items[0].note)
        assertEquals(listOf(false, false), items.map { it.pending })
    }

    @Test
    fun `to-do items carry the day they were added, for the date and the oldest count`() {
        val item = PhoneLists.todoItems(
            listOf(PhoneListItem(id = 3, listId = "todo", text = "Oil the gate", addedOnEpochDay = day.toEpochDay()))
        ).single()

        assertEquals(day, item.addedOn)
        assertEquals(3L, item.localId)
        assertNull(item.line)
        assertEquals(10L, RollingTodo.oldestOpenAgeDays(listOf(item), day.plusDays(10)))
    }

    @Test
    fun `a phone list's tile counts what's left, like a hub list's`() {
        val dto = PhoneLists.summary(PhoneListSummary("id-1", "Camping", total = 3, left = 2))

        assertEquals(ShoppingListSummaryDto("id-1", "Camping", total = 3, unticked = 2), dto)
        assertEquals(2, ShoppingList.itemsLeft(dto))
    }

    @Test
    fun `phone notes are titled as the hub titles its notes, and marked as on the phone`() {
        val row = PhoneLists.noteRows(listOf(PhoneNote("n1", "\n  Boiler questions \npressure\n\ndrop", 1L))).single()

        assertEquals("Boiler questions", row.title)
        assertEquals("pressure drop", row.preview)
        assertEquals(ListHome.PHONE, row.home)
        assertEquals(false, row.pending)
    }
}
