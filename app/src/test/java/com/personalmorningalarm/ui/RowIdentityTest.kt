package com.personalmorningalarm.ui

import com.personalmorningalarm.data.entity.ChalkboardVerb
import com.personalmorningalarm.data.entity.PendingChalkboardWrite
import com.personalmorningalarm.data.entity.PendingShoppingWrite
import com.personalmorningalarm.data.entity.ShoppingVerb
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.RollingTodo
import com.personalmorningalarm.data.model.ShoppingItemDto
import com.personalmorningalarm.data.model.ShoppingList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Identical rows are separate rows to the list diff, and a row keeps its identity through a tick. */
@RunWith(RobolectricTestRunner::class)
class RowIdentityTest {

    private val milk = ShoppingItemDto("Milk", "- [ ] Milk", false)
    private val milkTodo = ChalkboardTaskDto("Buy milk", "2026-11-06", "- [ ] Buy milk (2026-11-06)")

    private fun queuedShoppingTick() = PendingShoppingWrite(
        listId = "groceries", verb = ShoppingVerb.TICK.name, text = "Milk", line = "- [ ] Milk", createdAt = 0L
    )

    private fun queuedTodoTick() = PendingChalkboardWrite(
        verb = ChalkboardVerb.TICK.name, text = "Buy milk", line = milkTodo.line, createdAt = 0L
    )

    @Test
    fun `identical shopping rows are different items to the diff`() {
        val (first, second) = ShoppingList.merged(listOf(milk, milk), emptyList())

        assertEquals(listOf(0, 1), listOf(first.occurrence, second.occurrence))
        assertFalse(ShoppingItemAdapter.DIFF.areItemsTheSame(first, second))
    }

    @Test
    fun `a ticked shopping row is the same item with new contents, and its twin is untouched`() {
        val before = ShoppingList.merged(listOf(milk, milk), emptyList())
        val after = ShoppingList.merged(listOf(milk, milk), listOf(queuedShoppingTick()))

        assertTrue(ShoppingItemAdapter.DIFF.areItemsTheSame(before[0], after[0]))
        assertFalse(ShoppingItemAdapter.DIFF.areContentsTheSame(before[0], after[0]))
        assertTrue(ShoppingItemAdapter.DIFF.areItemsTheSame(before[1], after[1]))
        assertTrue(ShoppingItemAdapter.DIFF.areContentsTheSame(before[1], after[1]))
    }

    @Test
    fun `identical to-do rows are different items to the diff`() {
        val (first, second) = RollingTodo.merged(listOf(milkTodo, milkTodo), emptyList())

        assertEquals(listOf(0, 1), listOf(first.occurrence, second.occurrence))
        assertFalse(RollingTodoAdapter.DIFF.areItemsTheSame(first, second))
    }

    @Test
    fun `a ticked to-do row is the same item with new contents, and its twin is untouched`() {
        val before = RollingTodo.merged(listOf(milkTodo, milkTodo), emptyList())
        val after = RollingTodo.merged(listOf(milkTodo, milkTodo), listOf(queuedTodoTick()))

        assertTrue(RollingTodoAdapter.DIFF.areItemsTheSame(before[0], after[0]))
        assertFalse(RollingTodoAdapter.DIFF.areContentsTheSame(before[0], after[0]))
        assertTrue(RollingTodoAdapter.DIFF.areItemsTheSame(before[1], after[1]))
        assertTrue(RollingTodoAdapter.DIFF.areContentsTheSame(before[1], after[1]))
    }

    @Test
    fun `to-do rows with the same text but different dates each count from zero`() {
        val older = milkTodo.copy(date = "2026-11-01", line = "- [ ] Buy milk (2026-11-01)")

        val rows = RollingTodo.merged(listOf(older, milkTodo), emptyList())

        assertEquals(listOf(0, 0), rows.map { it.occurrence })
        assertFalse(RollingTodoAdapter.DIFF.areItemsTheSame(rows[0], rows[1]))
    }
}
