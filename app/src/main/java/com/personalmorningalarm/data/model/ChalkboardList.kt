package com.personalmorningalarm.data.model

/**
 * The hub's chalkboard lists: what `?list=` names on every /chalkboard route. The
 * hub keeps the names on a fixed allowlist and answers anything else with a 400.
 *
 * The app always sends the name, even for [NICK], which is also the hub's default:
 * a request that says which list it means can't land on the wrong one.
 */
enum class ChalkboardList(
    /** The `?list=` value, and what a queued write records. */
    val wireName: String,
    /** Where its last answer is saved. Nick's keeps the key it had before lists existed. */
    val cacheKey: String
) {
    /** Nick's rolling to-do (6-life/rolling-todo.md). */
    NICK("nick", "chalkboard"),

    /** The family chalkboard (6-life/family-chalkboard.md), which is also on the kitchen wall. */
    FAMILY("family", "chalkboard/family"),

    /**
     * Nick's day-job list (6-life/career/dochq/work-todo.md). Only a profile that
     * [com.personalmorningalarm.data.profile.Profile.mayUse] it is ever sent it.
     */
    WORK("work", "chalkboard/work");

    companion object {
        /** A name this build doesn't know is null: its queued writes fail rather than land on the wrong list. */
        fun fromWireName(name: String?): ChalkboardList? = entries.firstOrNull { it.wireName == name }
    }
}
