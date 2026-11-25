package com.personalmorningalarm.ui

import com.personalmorningalarm.data.profile.Profile
import android.content.Context
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.phone.ListHome
import android.widget.TextView
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

    private val phoneList = ShoppingListSummaryDto("0b6c-invented", "Camping", 2, 2)

    private val opened = mutableListOf<ShoppingListSummaryDto>()
    private val openedHomes = mutableListOf<ListHome>()
    private val pressed = mutableListOf<ShoppingListSummaryDto>()
    private var newListTaps = 0

    private val adapter = ShoppingListSummaryAdapter(
        me = Profile.NICK,
        onClick = { opened += it.summary; openedHomes += it.home },
        onLongPress = { pressed += it.summary },
        onNewList = { newListTaps++ }
    ).also { it.submit(listOf(list), listOf(phoneList)) }

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

    @Test
    fun `a phone list follows the hub's, says it's on this phone, and opens as a phone list`() {
        adapter.onBindViewHolder(holder, PHONE_TILE)

        holder.itemView.performClick()

        assertEquals(listOf(phoneList), opened)
        assertEquals(listOf(ListHome.PHONE), openedHomes)
        val count = holder.itemView.findViewById<TextView>(R.id.tv_shopping_list_count).text.toString()
        assertEquals("2 left · this phone", count)
    }

    @Test
    fun `the New list tile always opens, since a phone list needs no Alfred`() {
        ShoppingListSummaryAdapter(Profile.NICK, {}, {}, { newListTaps++ }).also {
            it.submit(null)
            it.onBindViewHolder(holder, 0)
        }

        holder.itemView.performClick()

        assertEquals(1, newListTaps)
    }


    /** The count line [me] sees on a tile for [summary]. */
    private fun countLine(me: Profile, summary: ShoppingListSummaryDto): String {
        val tiles = ShoppingListSummaryAdapter(me, {}, {}, {}).also { it.submit(listOf(summary)) }
        // Its own holder: a holder reads the profile of the adapter that made it.
        val own = tiles.onCreateViewHolder(FrameLayout(context), 0)
        tiles.onBindViewHolder(own, 0)
        return own.itemView.findViewById<TextView>(R.id.tv_shopping_list_count).text.toString()
    }

    @Test
    fun `the other person's shared list names its owner, and your own shared list says shared`() {
        val nicks = ShoppingListSummaryDto("6-life/shopping/hardware.md", "Hardware", 2, 2, owner = "nick", shared = true)

        assertEquals("Nick’s · 2 left", countLine(Profile.JESS, nicks))
        assertEquals("Shared · 2 left", countLine(Profile.NICK, nicks))
        assertEquals("2 left", countLine(Profile.NICK, nicks.copy(shared = false)))
        // A list from before owners existed is Nick's.
        assertEquals("Nick’s · 2 left", countLine(Profile.JESS, nicks.copy(owner = null)))
    }

    private companion object {
        const val LIST_TILE = 0
        const val PHONE_TILE = 1
        const val NEW_LIST_TILE = 2
    }
}
