package com.personalmorningalarm.data.phone

import android.content.Context
import com.personalmorningalarm.data.remote.AlfredResponseCache
import com.personalmorningalarm.data.remote.AlfredSettings
import com.personalmorningalarm.util.ThemeManager

/**
 * Where new lists go, and where the to-do lives. Kept with the other app settings.
 *
 * A hub counts as configured when an address was saved in Settings, or when Alfred
 * has answered at the built-in default address. The second matters: a phone whose
 * hub sits at the default never saves an address (Nick's A32 is one), and it has a
 * hub all the same. A built-in address nobody has ever answered at is only a guess,
 * so on its own it doesn't count.
 */
class ListSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(ThemeManager.PREFS_NAME, Context.MODE_PRIVATE)

    private val alfred = AlfredSettings(context)
    private val cache = AlfredResponseCache(context)

    fun hubConfigured(): Boolean = alfred.isConfigured() || cache.hasAny()

    /** What the "where does it live?" question starts on: the hub if there is one. */
    fun defaultHome(): ListHome = defaultHome(hubConfigured())

    /**
     * The to-do tile's list. Until it's chosen in Settings it follows [defaultHome],
     * and that first answer is kept, so the to-do never moves by itself: a phone with
     * a hub keeps the hub's rolling to-do, whatever later happens to the saved copies.
     */
    fun todoHome(): ListHome {
        prefs.getString(KEY_TODO_HOME, null)?.let { return ListHome.fromName(it) }
        return defaultHome().also(::setTodoHome)
    }

    fun setTodoHome(home: ListHome) {
        prefs.edit().putString(KEY_TODO_HOME, home.name).apply()
    }

    companion object {
        private const val KEY_TODO_HOME = "todo_home"

        fun defaultHome(hubConfigured: Boolean): ListHome = if (hubConfigured) ListHome.HUB else ListHome.PHONE
    }
}
