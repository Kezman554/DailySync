package com.personalmorningalarm.ui

import com.personalmorningalarm.data.remote.AlfredResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * How old the Alfred data on a surface is, and how loudly to say so. Worked out
 * with no Android in sight so the thresholds can be tested on the JVM;
 * FreshnessView only paints the answer.
 *
 * The visual weight tracks the risk of the data being believed:
 *  - [Level.NONE]: live, fetched under 5 minutes ago. No furniture at all.
 *  - [Level.QUIET]: a saved copy under an hour old, or live data left on screen
 *    5–60 minutes. One secondary-colour line.
 *  - [Level.PROMINENT]: an hour old or more, still from today. A primary-colour
 *    line naming the time it's from.
 *  - [Level.LOUD]: fetched on an earlier calendar day — which takes in anything
 *    24 h old or more. An error-outlined notice, and an error-outlined card on a
 *    tile. This is the case that misled someone: during a Pi outage the app
 *    served an eleven-day-old plan as if it were today's.
 *
 * LOUD goes by calendar day rather than by 24 hours because the feeds are
 * day-scoped: a schedule fetched at 21:00 is yesterday's plan at 06:00, nine
 * hours later, and that is exactly the morning it would be read.
 *
 * A saved copy (the live fetch failed) is never NONE, however young: "fresh",
 * "saved" and "never fetched" are three states and each looks different. Never
 * fetched — [AlfredResult.Unavailable] — has no age; the surface shows no data
 * at all and says why instead.
 */
object Freshness {

    enum class Level { NONE, QUIET, PROMINENT, LOUD }

    /** Live data this old starts saying so. */
    const val QUIET_AFTER_MS = 5 * 60 * 1000L

    /** From an hour old the line names the time and steps up to primary text. */
    const val PROMINENT_AFTER_MS = 60 * 60 * 1000L

    data class Age(
        val level: Level,
        val fetchedAtMillis: Long,
        val ageMillis: Long,
        /** A saved copy shown because the live fetch failed, not live data growing old on screen. */
        val saved: Boolean,
        /** Calendar days between the fetch and today: 0 today, 1 yesterday. */
        val daysAgo: Long
    )

    /** How old [result] is at [nowMillis]; null when there's nothing on screen to age (loading, never fetched). */
    fun of(result: AlfredResult<*>?, nowMillis: Long, zone: ZoneId): Age? = when (result) {
        is AlfredResult.Fresh -> age(result.fetchedAtMillis, saved = false, nowMillis, zone)
        is AlfredResult.Stale -> age(result.cachedAtMillis, saved = true, nowMillis, zone)
        AlfredResult.Unavailable, null -> null
    }

    private fun age(fetchedAt: Long, saved: Boolean, nowMillis: Long, zone: ZoneId): Age {
        // A fetch "in the future" is a clock change, not newer data — read it as just now.
        val ageMillis = (nowMillis - fetchedAt).coerceAtLeast(0)
        val daysAgo = ChronoUnit.DAYS.between(dateOf(fetchedAt, zone), dateOf(nowMillis, zone))
            .coerceAtLeast(0)
        val level = when {
            daysAgo >= 1 -> Level.LOUD
            ageMillis >= PROMINENT_AFTER_MS -> Level.PROMINENT
            saved || ageMillis >= QUIET_AFTER_MS -> Level.QUIET
            else -> Level.NONE
        }
        return Age(level, fetchedAt, ageMillis, saved, daysAgo)
    }

    private fun dateOf(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /**
     * The one a surface built from several feeds should show: the loudest, and
     * among equals a saved copy over live data, then the oldest.
     */
    fun worst(vararg ages: Age?): Age? =
        ages.filterNotNull().maxWithOrNull(
            compareBy<Age> { it.level }.thenBy { it.saved }.thenBy { it.ageMillis }
        )

    /**
     * Whether an open surface should ask Alfred again: it's showing a saved copy,
     * or live data that has grown an hour old. This is what lets the warnings
     * clear themselves once Alfred is back.
     */
    fun wantsRetry(age: Age?): Boolean = age != null && (age.saved || age.level >= Level.PROMINENT)

    /** The age as a count of one unit, the way a line on screen reads it. */
    sealed interface Span {
        data object UnderAMinute : Span
        data class Minutes(val count: Int) : Span
        data class Hours(val count: Int) : Span
        data class Days(val count: Int) : Span
    }

    /**
     * Minutes under an hour, hours under a day, then whole calendar days — so
     * yesterday 21:00 read at 06:00 is "1 day", matching the LOUD rule rather
     * than "9 h".
     */
    fun span(age: Age): Span {
        val minutes = age.ageMillis / 60_000
        return when {
            age.daysAgo >= 1 -> Span.Days(age.daysAgo.toInt())
            minutes < 1 -> Span.UnderAMinute
            minutes < 60 -> Span.Minutes(minutes.toInt())
            else -> Span.Hours((minutes / 60).toInt())
        }
    }
}
