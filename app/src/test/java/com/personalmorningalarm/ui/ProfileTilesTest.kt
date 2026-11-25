package com.personalmorningalarm.ui

import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.ui.HomeTile.INBOX
import com.personalmorningalarm.ui.HomeTile.KANBAN
import com.personalmorningalarm.ui.HomeTile.KITCHEN_SYNC
import com.personalmorningalarm.ui.HomeTile.NOTES
import com.personalmorningalarm.ui.HomeTile.SCHEDULE
import com.personalmorningalarm.ui.HomeTile.SHOPPING
import com.personalmorningalarm.ui.HomeTile.SIGN_IN
import com.personalmorningalarm.ui.HomeTile.TODO
import com.personalmorningalarm.ui.ProfileTiles.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which tiles each profile shows, in what order, and how they lay out. */
class ProfileTilesTest {

    @Test
    fun `Nick's Home is the one it always was, in its old order`() {
        assertEquals(
            listOf(SCHEDULE, TODO, SHOPPING, NOTES, INBOX, SIGN_IN, KITCHEN_SYNC, KANBAN),
            ProfileTiles.tilesFor(Profile.NICK)
        )
    }

    @Test
    fun `Nick's tiles lay out as the old fixed layout did`() {
        // Schedule card; bento of To-Do over Notes beside Shopping over Inbox;
        // the sign-in card; the two coming-soon tiles side by side.
        assertEquals(
            listOf(
                Row.Wide(SCHEDULE),
                Row.Bento(left = listOf(TODO, NOTES), right = listOf(SHOPPING, INBOX)),
                Row.Wide(SIGN_IN),
                Row.ComingSoon(listOf(KITCHEN_SYNC, KANBAN))
            ),
            ProfileTiles.rows(ProfileTiles.tilesFor(Profile.NICK))
        )
    }

    @Test
    fun `Jess has no Inbox, no Home Hub Sign-in and no Daily Schedule card`() {
        val jess = ProfileTiles.tilesFor(Profile.JESS)

        listOf(INBOX, SIGN_IN, SCHEDULE).forEach { assertFalse("$it shown to Jess", it in jess) }
    }

    @Test
    fun `Jess keeps her lists, her notes and the coming-soon tiles, in order`() {
        assertEquals(
            listOf(TODO, SHOPPING, NOTES, KITCHEN_SYNC, KANBAN),
            ProfileTiles.tilesFor(Profile.JESS)
        )
        assertEquals(
            listOf(
                Row.Bento(left = listOf(TODO, NOTES), right = listOf(SHOPPING)),
                Row.ComingSoon(listOf(KITCHEN_SYNC, KANBAN))
            ),
            ProfileTiles.rows(ProfileTiles.tilesFor(Profile.JESS))
        )
    }

    @Test
    fun `before the first-run choice Home shows nothing, so nothing is fetched`() {
        assertTrue(ProfileTiles.tilesFor(null).isEmpty())
        assertTrue(ProfileTiles.rows(emptyList()).isEmpty())
    }

    @Test
    fun `no profile shows a tile twice`() {
        Profile.values().forEach { profile ->
            val tiles = ProfileTiles.tilesFor(profile)
            assertEquals("$profile repeats a tile", tiles.size, tiles.toSet().size)
        }
    }

    @Test
    fun `rows keep the given order, whatever it is`() {
        assertEquals(
            listOf(
                Row.ComingSoon(listOf(KANBAN)),
                Row.Bento(left = listOf(NOTES), right = emptyList()),
                Row.Wide(SIGN_IN),
                Row.Wide(SCHEDULE),
                Row.Bento(left = listOf(INBOX, SHOPPING), right = listOf(TODO))
            ),
            ProfileTiles.rows(listOf(KANBAN, NOTES, SIGN_IN, SCHEDULE, INBOX, TODO, SHOPPING))
        )
    }

    @Test
    fun `coming-soon tiles go two to a row`() {
        assertEquals(
            listOf(Row.ComingSoon(listOf(KITCHEN_SYNC, KANBAN)), Row.ComingSoon(listOf(KITCHEN_SYNC))),
            ProfileTiles.rows(listOf(KITCHEN_SYNC, KANBAN, KITCHEN_SYNC))
        )
    }
}
