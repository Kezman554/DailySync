package com.personalmorningalarm.data.profile

import android.content.Context
import com.personalmorningalarm.data.phone.ListSettings
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResponseCache
import com.personalmorningalarm.util.AlarmScheduler

/**
 * Choosing a profile, at first run or in Settings. The caller recreates the screen
 * afterwards, so everything that reads the profile reads it afresh.
 */
object ProfileSetup {

    fun choose(context: Context, profile: Profile) {
        val settings = ProfileSettings(context)
        val previous = settings.profile()
        settings.setProfile(profile)

        // Setting up: the profile's starting to-do (Jess's lives on the phone). A
        // later switch in Settings leaves the to-do where it is.
        if (previous == null) profile.setupTodoHome?.let(ListSettings(context)::setTodoHome)

        // The saved copy of the hub's lists was asked for as the other person.
        if (previous != null && previous != profile) {
            AlfredResponseCache(context).clear(AlfredRepository.ENDPOINT_SHOPPING)
        }

        // An alarm armed as Nick doesn't survive the switch. His saved alarm stays,
        // and is re-armed when the app next opens as Nick.
        if (!profile.hasAlarm) AlarmScheduler(context).cancelAlarm()
    }
}
