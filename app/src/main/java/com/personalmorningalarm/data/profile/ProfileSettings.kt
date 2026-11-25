package com.personalmorningalarm.data.profile

import android.content.Context
import com.personalmorningalarm.util.ThemeManager

/**
 * The saved [Profile], kept with the other app settings (so it is backed up with
 * them). Three states: Nick, Jess, or waiting for the first-run choice.
 *
 * An install from before profiles existed has no saved profile, and must carry on
 * as Nick's without asking. [settle] tells the two apart once, at process start,
 * before anything opens the database: an existing install already has one, a fresh
 * install doesn't. The answer is written down at once, so a fresh install that
 * creates its database and is closed before the choice still asks next time.
 */
class ProfileSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(ThemeManager.PREFS_NAME, Context.MODE_PRIVATE)

    /** The chosen profile, or null while the first-run choice is still to be made. */
    fun profile(): Profile? = Profile.fromWireName(prefs.getString(KEY_PROFILE, null))

    fun setProfile(profile: Profile) {
        prefs.edit().putString(KEY_PROFILE, profile.wireName).apply()
    }

    /** Whether [profile] allows [permission]; nothing is allowed before the choice. */
    fun allows(permission: AlarmPermission): Boolean = Profile.allows(profile(), permission)

    /**
     * Run once per process start, before the database is opened. [existingInstall]
     * is whether the alarm database was already on disk.
     */
    fun settle(existingInstall: Boolean) {
        if (prefs.contains(KEY_PROFILE)) return
        prefs.edit()
            .putString(KEY_PROFILE, if (existingInstall) Profile.NICK.wireName else PENDING)
            .commit()
    }

    companion object {
        private const val KEY_PROFILE = "profile"
        private const val PENDING = "pending"
    }
}
