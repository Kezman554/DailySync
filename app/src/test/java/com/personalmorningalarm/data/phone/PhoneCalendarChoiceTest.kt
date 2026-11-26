package com.personalmorningalarm.data.phone

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which phone calendar "My calendar" means, and that Young Family is never one of them. */
class PhoneCalendarChoiceTest {

    private val personal = PhoneCalendar(1, "Personal", "me@example.com")
    private val work = PhoneCalendar(2, "Work", "me@example.com")
    private val family = PhoneCalendar(3, "Young Family", "me@example.com")

    @Test
    fun `Young Family is left out when it's synced to the phone`() {
        assertEquals(listOf(personal, work), PhoneCalendarChoice.offered(listOf(personal, family, work)))
    }

    @Test
    fun `Young Family is recognised whatever its case or spacing, or by its server name after a local rename`() {
        val shouty = PhoneCalendar(4, " young family ", "me@example.com")
        val renamed = PhoneCalendar(5, "Us lot", "me@example.com", serverName = "Young Family")
        assertEquals(listOf(personal), PhoneCalendarChoice.offered(listOf(personal, shouty, renamed)))
    }

    @Test
    fun `one calendar is used without asking`() {
        assertEquals(PhoneCalendarPick.Use(personal), PhoneCalendarChoice.resolve(listOf(personal), null))
    }

    @Test
    fun `one calendar besides Young Family is used without asking`() {
        assertEquals(PhoneCalendarPick.Use(personal), PhoneCalendarChoice.resolve(listOf(family, personal), null))
    }

    @Test
    fun `several with none chosen asks, without Young Family in the list`() {
        assertEquals(
            PhoneCalendarPick.Ask(listOf(personal, work)),
            PhoneCalendarChoice.resolve(listOf(personal, family, work), null)
        )
    }

    @Test
    fun `the remembered calendar is used`() {
        assertEquals(PhoneCalendarPick.Use(work), PhoneCalendarChoice.resolve(listOf(personal, work), work.id))
    }

    @Test
    fun `a remembered calendar that's gone asks again`() {
        assertEquals(
            PhoneCalendarPick.Ask(listOf(personal, work)),
            PhoneCalendarChoice.resolve(listOf(personal, work), 99)
        )
    }

    @Test
    fun `a remembered Young Family is never used`() {
        assertEquals(
            PhoneCalendarPick.Ask(listOf(personal, work)),
            PhoneCalendarChoice.resolve(listOf(personal, work, family), family.id)
        )
    }

    @Test
    fun `none, or only Young Family, is none`() {
        assertEquals(PhoneCalendarPick.None, PhoneCalendarChoice.resolve(emptyList(), null))
        assertEquals(PhoneCalendarPick.None, PhoneCalendarChoice.resolve(listOf(family), null))
    }
}
