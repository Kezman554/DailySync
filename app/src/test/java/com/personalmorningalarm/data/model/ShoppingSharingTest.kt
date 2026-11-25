package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.model.ShoppingSharing.Toggle
import com.personalmorningalarm.data.profile.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whose list it is, and what changing its sharing means for each person. */
class ShoppingSharingTest {

    private fun list(owner: String?, shared: Boolean? = true) =
        ShoppingListSummaryDto("6-life/shopping/x.md", "X", 1, 1, owner, shared)

    @Test
    fun `no owner is Nick's, as the hub reads lists written before owners`() {
        assertEquals(Profile.NICK, ShoppingSharing.ownerOf(list(null)))
        assertEquals(Profile.NICK, ShoppingSharing.ownerOf(list(" ")))
        assertEquals(Profile.JESS, ShoppingSharing.ownerOf(list("Jess")))
        assertNull(ShoppingSharing.ownerOf(list("oliver")))
    }

    @Test
    fun `shared only when it says so`() {
        assertTrue(ShoppingSharing.isShared(list("nick", true)))
        assertFalse(ShoppingSharing.isShared(list("nick", false)))
        assertFalse(ShoppingSharing.isShared(list("nick", null)))
    }

    @Test
    fun `Jess unsharing her list pulls it back, Nick unsharing his clears the flag`() {
        assertEquals(Toggle.PULL_BACK, ShoppingSharing.toggle(Profile.JESS, list("jess"), shared = false))
        assertEquals(Toggle.SET_FLAG, ShoppingSharing.toggle(Profile.NICK, list("nick"), shared = false))
    }

    @Test
    fun `sharing a hub list of your own is the flag, for either person`() {
        assertEquals(Toggle.SET_FLAG, ShoppingSharing.toggle(Profile.JESS, list("jess", false), shared = true))
        assertEquals(Toggle.SET_FLAG, ShoppingSharing.toggle(Profile.NICK, list("nick", false), shared = true))
    }

    @Test
    fun `only the owner changes a list's sharing, and nobody changes a list owned by a stranger`() {
        assertEquals(Toggle.NOT_OWNER, ShoppingSharing.toggle(Profile.JESS, list("nick"), shared = false))
        assertEquals(Toggle.NOT_OWNER, ShoppingSharing.toggle(Profile.NICK, list("jess"), shared = false))
        assertEquals(Toggle.NOT_OWNER, ShoppingSharing.toggle(Profile.NICK, list("oliver"), shared = false))
        assertEquals(Toggle.NOT_OWNER, ShoppingSharing.toggle(Profile.JESS, list("oliver"), shared = true))
    }
}
