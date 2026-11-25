package com.personalmorningalarm.data.profile

import com.personalmorningalarm.data.phone.ListHome

/**
 * Whose phone this is. Chosen once at first run (existing installs are Nick's) and
 * changeable in Settings. What each profile shows is decided here and in
 * [com.personalmorningalarm.ui.ProfileTiles], never by a screen asking "is this Jess?".
 *
 * Nick's profile is the app as it always was. Jess's has no wake-up alarm at all:
 * no Alarm tab, no NFC tags, nuclear mode, PIN or dev tools (all behind that tab),
 * no scheduling, and none of the permissions the alarm needs.
 */
enum class Profile(
    /** What the hub calls this person: the `for=` on every shopping request. */
    val wireName: String
) {
    NICK("nick"),
    JESS("jess");

    /** The wake-up alarm and everything that serves it. */
    val hasAlarm: Boolean get() = this == NICK

    /** Whether this profile may ask for, or act on, [permission]. */
    fun allows(permission: AlarmPermission): Boolean = hasAlarm

    /**
     * Where a new shopping list or note goes. Nick's always go to the hub, with no
     * question, as they did before lists could live on the phone. Jess is asked,
     * starting on the phone.
     */
    val newListHome: NewListHome
        get() = when (this) {
            NICK -> NewListHome.Always(ListHome.HUB)
            JESS -> NewListHome.Ask(ListHome.PHONE)
        }

    /**
     * Where the To-do tile's Mine list lives, given Settings' choice [setting]. The
     * hub's rolling to-do is Nick's own list, so only Nick's Mine can be it (and is,
     * unless he moved it to the phone); Jess's Mine is always her phone list.
     */
    fun mineTodoHome(setting: ListHome): ListHome = when (this) {
        NICK -> setting
        JESS -> ListHome.PHONE
    }

    /** Whether Settings offers the "To-Do list lives on" choice: only where [mineTodoHome] heeds it. */
    val choosesTodoHome: Boolean get() = this == NICK

    /** Where the to-do starts when this profile is set up, or null to leave it be. */
    val setupTodoHome: ListHome?
        get() = when (this) {
            NICK -> null
            JESS -> ListHome.PHONE
        }

    companion object {
        fun fromWireName(name: String?): Profile? = values().firstOrNull { it.wireName == name }

        /** No profile yet (first run, before the choice) allows nothing. */
        fun allows(profile: Profile?, permission: AlarmPermission): Boolean =
            profile?.allows(permission) == true
    }
}

/**
 * Every permission or prompt the app has only for the alarm's sake. A profile
 * without the alarm requests none of them. Declared in the manifest they still are
 * (one APK serves both), but those are install-time grants that show nothing; the
 * runtime requests and prompts are what a profile gates.
 */
enum class AlarmPermission {
    /** Alarms & reminders (SCHEDULE_EXACT_ALARM): the Alarm tab's prompt, and any scheduling. */
    EXACT_ALARM,
    /** Reader mode for NFC checkpoints and tag registration. */
    NFC,
    /** The alarm's full-screen intent over the lock screen. */
    FULL_SCREEN_INTENT,
    /** The first-run "don't optimise battery" prompt, and Settings' Alarm health row. */
    BATTERY_OPTIMISATION,
    /** POST_NOTIFICATIONS: asked at start because the alarm can't be dismissed without it. */
    NOTIFICATIONS
}

/** Whether creating a list or note asks where it lives. */
sealed class NewListHome {
    data class Always(val home: ListHome) : NewListHome()
    data class Ask(val initial: ListHome) : NewListHome()
}
