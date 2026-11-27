package com.personalmorningalarm.ui

import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.ui.HomeTile.CALENDAR
import com.personalmorningalarm.ui.HomeTile.INBOX
import com.personalmorningalarm.ui.HomeTile.KANBAN
import com.personalmorningalarm.ui.HomeTile.MEALS
import com.personalmorningalarm.ui.HomeTile.NOTES
import com.personalmorningalarm.ui.HomeTile.SCHEDULE
import com.personalmorningalarm.ui.HomeTile.SHOPPING
import com.personalmorningalarm.ui.HomeTile.SIGN_IN
import com.personalmorningalarm.ui.HomeTile.TODAY
import com.personalmorningalarm.ui.HomeTile.TODO
import com.personalmorningalarm.ui.ProfileTiles.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which tiles each profile shows, in what order, and how they lay out. */
class ProfileTilesTest {

    @Test
    fun `Nick's Home is the one it always was, in its old order, less the KitchenSync placeholder`() {
        // KitchenSync's coming-soon tile went in BK: Groceries is in Shopping now.
        assertEquals(
            listOf(SCHEDULE, TODO, SHOPPING, NOTES, INBOX, SIGN_IN, KANBAN),
            ProfileTiles.tilesFor(Profile.NICK)
        )
    }

    @Test
    fun `Nick's tiles lay out as the old fixed layout did`() {
        // Schedule card; bento of To-Do over Notes beside Shopping over Inbox;
        // the sign-in card; Kanban, the one coming-soon tile left.
        assertEquals(
            listOf(
                Row.Wide(SCHEDULE),
                Row.Bento(left = listOf(TODO, NOTES), right = listOf(SHOPPING, INBOX)),
                Row.Wide(SIGN_IN),
                Row.ComingSoon(listOf(KANBAN))
            ),
            ProfileTiles.rows(ProfileTiles.tilesFor(Profile.NICK), ProfileTiles.tallFor(Profile.NICK))
        )
    }

    @Test
    fun `Nick has no tall tile, and none of Jess's new tiles`() {
        assertEquals(null, ProfileTiles.tallFor(Profile.NICK))
        val nick = ProfileTiles.tilesFor(Profile.NICK)
        listOf(TODAY, CALENDAR, MEALS).forEach { assertFalse("$it shown to Nick", it in nick) }
    }

    @Test
    fun `Jess has no Inbox, no Home Hub Sign-in, no Daily Schedule card and no Kanban`() {
        val jess = ProfileTiles.tilesFor(Profile.JESS)

        listOf(INBOX, SIGN_IN, SCHEDULE, KANBAN).forEach { assertFalse("$it shown to Jess", it in jess) }
    }

    @Test
    fun `Jess's Home is Today, then To-do, Shopping, Notes, Calendar and Meals`() {
        assertEquals(
            listOf(TODAY, TODO, SHOPPING, NOTES, CALENDAR, MEALS),
            ProfileTiles.tilesFor(Profile.JESS)
        )
    }

    @Test
    fun `Jess's To-do stands tall beside Shopping over Notes, Calendar full width, Meals coming soon`() {
        assertEquals(
            listOf(
                Row.Wide(TODAY),
                Row.Bento(left = listOf(TODO), right = listOf(SHOPPING, NOTES)),
                Row.Wide(CALENDAR),
                Row.ComingSoon(listOf(MEALS))
            ),
            ProfileTiles.rows(ProfileTiles.tilesFor(Profile.JESS), ProfileTiles.tallFor(Profile.JESS))
        )
    }

    @Test
    fun `a tall tile outside the run changes nothing`() {
        assertEquals(
            listOf(Row.Bento(left = listOf(SHOPPING), right = listOf(NOTES))),
            ProfileTiles.rows(listOf(SHOPPING, NOTES), tall = TODO)
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
            listOf(Row.ComingSoon(listOf(MEALS, KANBAN)), Row.ComingSoon(listOf(MEALS))),
            ProfileTiles.rows(listOf(MEALS, KANBAN, MEALS))
        )
    }
}
