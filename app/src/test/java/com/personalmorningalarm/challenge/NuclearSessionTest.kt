package com.personalmorningalarm.challenge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A rebuilt nuclear screen must find the gauntlet where the old one left it, and a
 * new alarm must start from nothing.
 */
class NuclearSessionTest {

    @Before
    @After
    fun reset() = NuclearSession.clear()

    @Test
    fun `a rebuilt screen gets the same session, progress and maths problem`() {
        val first = NuclearSession.obtain()
        first.shakeDone = true
        first.mathDone = true

        val second = NuclearSession.obtain()

        assertSame(first, second)
        assertTrue(second.shakeDone)
        assertTrue(second.mathDone)
        assertFalse(second.typeDone)
        assertEquals(first.math.questionText, second.math.questionText)
    }

    @Test
    fun `clearing starts the next alarm from nothing`() {
        val first = NuclearSession.obtain()
        first.shakeDone = true

        NuclearSession.clear()
        val next = NuclearSession.obtain()

        assertNotSame(first, next)
        assertFalse(next.shakeDone)
    }

    @Test
    fun `all three must be done`() {
        val session = NuclearSession.obtain()
        assertFalse(session.allDone)
        session.shakeDone = true
        session.mathDone = true
        assertFalse(session.allDone)
        session.typeDone = true
        assertTrue(session.allDone)
    }
}
