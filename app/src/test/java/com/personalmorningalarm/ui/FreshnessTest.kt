package com.personalmorningalarm.ui

import com.personalmorningalarm.data.remote.AlfredResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The escalation rules: the line's weight tracks the risk of the data being
 * believed, and fresh, saved and never-fetched stay three different things.
 */
class FreshnessTest {

    private val zone = ZoneId.of("Europe/London")

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private val now = at("2026-10-18T06:30:00")

    private fun live(fetched: String) = Freshness.of(AlfredResult.Fresh(Unit, at(fetched)), now, zone)!!
    private fun saved(fetched: String) = Freshness.of(AlfredResult.Stale(Unit, at(fetched)), now, zone)!!

    @Test
    fun `live data under five minutes old has no furniture`() {
        assertEquals(Freshness.Level.NONE, live("2026-10-18T06:26:00").level)
    }

    @Test
    fun `live data left on screen five minutes starts saying so quietly`() {
        assertEquals(Freshness.Level.QUIET, live("2026-10-18T06:25:00").level)
        assertEquals(Freshness.Level.QUIET, live("2026-10-18T05:31:00").level)
    }

    @Test
    fun `a saved copy is never silent however young`() {
        val age = saved("2026-10-18T06:29:50")
        assertEquals(Freshness.Level.QUIET, age.level)
        assertTrue(age.saved)
        assertEquals(Freshness.Span.UnderAMinute, Freshness.span(age))
    }

    @Test
    fun `an hour old steps up to prominent`() {
        val age = saved("2026-10-18T05:30:00")
        assertEquals(Freshness.Level.PROMINENT, age.level)
        assertEquals(Freshness.Span.Hours(1), Freshness.span(age))
    }

    @Test
    fun `anything from an earlier day is loud even when under 24 hours old`() {
        // Yesterday evening's plan, read at 06:30: nine and a half hours, but not today's.
        val age = saved("2026-10-17T21:00:00")
        assertEquals(Freshness.Level.LOUD, age.level)
        assertEquals(1L, age.daysAgo)
        assertEquals(Freshness.Span.Days(1), Freshness.span(age))
    }

    @Test
    fun `the August outage - eleven days old is loud`() {
        val age = saved("2026-10-07T07:40:00")
        assertEquals(Freshness.Level.LOUD, age.level)
        assertEquals(Freshness.Span.Days(11), Freshness.span(age))
    }

    @Test
    fun `live data left open overnight is loud too`() {
        assertEquals(Freshness.Level.LOUD, live("2026-10-17T23:59:00").level)
    }

    @Test
    fun `a cache with no recorded time reads as ancient, not fresh`() {
        assertEquals(Freshness.Level.LOUD, Freshness.of(AlfredResult.Stale(Unit, 0L), now, zone)!!.level)
    }

    @Test
    fun `a fetch time in the future reads as just now`() {
        val age = live("2026-10-18T06:40:00")
        assertEquals(0L, age.ageMillis)
        assertEquals(Freshness.Level.NONE, age.level)
    }

    @Test
    fun `never fetched and loading have no age at all`() {
        assertNull(Freshness.of(AlfredResult.Unavailable, now, zone))
        assertNull(Freshness.of(null, now, zone))
    }

    @Test
    fun `a surface built from two feeds shows the worse one`() {
        val plan = live("2026-10-18T06:29:00")
        val calendar = saved("2026-10-16T08:00:00")
        assertEquals(calendar, Freshness.worst(plan, calendar))
        assertEquals(plan, Freshness.worst(plan, null))
        assertNull(Freshness.worst(null, null))
    }

    @Test
    fun `at the same level a saved copy outranks live data`() {
        val liveQuiet = live("2026-10-18T06:00:00")
        val savedQuiet = saved("2026-10-18T06:20:00")
        assertEquals(savedQuiet, Freshness.worst(liveQuiet, savedQuiet))
    }

    @Test
    fun `a surface retries while saved or an hour old, never while live and current`() {
        assertTrue(Freshness.wantsRetry(saved("2026-10-18T06:29:00")))
        assertTrue(Freshness.wantsRetry(live("2026-10-18T05:00:00")))
        assertFalse(Freshness.wantsRetry(live("2026-10-18T06:10:00")))
        assertFalse(Freshness.wantsRetry(null))
    }

    @Test
    fun `minutes under an hour`() {
        assertEquals(Freshness.Span.Minutes(42), Freshness.span(saved("2026-10-18T05:48:00")))
    }
}
