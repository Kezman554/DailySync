package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.phone.ListHome
import com.personalmorningalarm.data.profile.Profile

/**
 * Who a shopping list belongs to and what sharing it means, from the hub's summary.
 * No Android and no network: the screens and [com.personalmorningalarm.data.remote.ShoppingShare]
 * both ask here.
 */
object ShoppingSharing {

    /** What the share toggle does to a hub list. */
    enum class Toggle {
        /** Set the hub's shared flag (on, or off for a list whose owner keeps it on the hub). */
        SET_FLAG,
        /** Unshare by moving the list back to the owner's phone. */
        PULL_BACK,
        /** Not this person's list: only its owner can change its sharing. */
        NOT_OWNER
    }

    /**
     * The list's owner. No owner on the list means Nick's: the hub's default for lists
     * written before owners existed. A name the app doesn't know is nobody's (null),
     * and nobody here can change its sharing.
     */
    fun ownerOf(list: ShoppingListSummaryDto): Profile? {
        val name = list.owner?.trim()?.lowercase()
        return if (name.isNullOrEmpty()) Profile.NICK else Profile.fromWireName(name)
    }

    fun isShared(list: ShoppingListSummaryDto): Boolean = list.shared == true

    fun isMine(list: ShoppingListSummaryDto, me: Profile): Boolean = ownerOf(list) == me

    /** What turning [list]'s sharing to [shared] means for [me]. */
    fun toggle(me: Profile, list: ShoppingListSummaryDto, shared: Boolean): Toggle = when {
        !isMine(list, me) -> Toggle.NOT_OWNER
        !shared && me.unsharedListHome == ListHome.PHONE -> Toggle.PULL_BACK
        else -> Toggle.SET_FLAG
    }
}
