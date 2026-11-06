package com.personalmorningalarm.ui

import android.content.Context
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A tap opens a list, a long press offers to delete it, and the New list tile has no long press. */
@RunWith(RobolectricTestRunner::class)
class ShoppingListSummaryAdapterTest {

    private val context: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(),
        R.style.Theme_PersonalMorningAlarm
    )
    private val list = ShoppingListSummaryDto("garden-tools.md", "Garden Tools", 5, 3)

    private val opened = mutableListOf<ShoppingListSummaryDto>()
    private val pressed = mutableListOf<ShoppingListSummaryDto>()
    private var newListTaps = 0

    private val adapter = ShoppingListSummaryAdapter(
        onClick = { opened += it },
        onLongPress = { pressed += it },
        onNewList = { newListTaps++ }
    ).also { it.submit(listOf(list), canCreate = true) }

    private val parent = FrameLayout(context)
    private val holder = adapter.onCreateViewHolder(parent, 0).also { parent.addView(it.itemView) }

    @Test
    fun `a tap on a list tile opens it and offers no delete`() {
        adapter.onBindViewHolder(holder, LIST_TILE)

        holder.itemView.performClick()

        assertEquals(listOf(list), opened)
        assertTrue(pressed.isEmpty())
    }

    @Test
    fun `a long press on a list tile offers the delete and does not open it`() {
        adapter.onBindViewHolder(holder, LIST_TILE)

        assertTrue(holder.itemView.performLongClick())

        assertEquals(listOf(list), pressed)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `the New list tile has no long press, even on a holder recycled from a list`() {
        adapter.onBindViewHolder(holder, LIST_TILE)
        adapter.onBindViewHolder(holder, NEW_LIST_TILE)

        assertFalse(holder.itemView.isLongClickable)
        holder.itemView.performLongClick()

        assertTrue(pressed.isEmpty())
    }

    @Test
    fun `a list tile gets its long press back on a holder recycled from New list`() {
        adapter.onBindViewHolder(holder, NEW_LIST_TILE)
        adapter.onBindViewHolder(holder, LIST_TILE)

        assertTrue(holder.itemView.isLongClickable)
        holder.itemView.performLongClick()

        assertEquals(listOf(list), pressed)
    }

    private companion object {
        const val LIST_TILE = 0
        const val NEW_LIST_TILE = 1
    }
}
