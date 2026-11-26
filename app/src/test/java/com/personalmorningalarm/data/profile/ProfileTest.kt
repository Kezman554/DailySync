package com.personalmorningalarm.data.profile

import com.personalmorningalarm.data.model.TodoSource
import com.personalmorningalarm.data.model.TodoFilter
import com.personalmorningalarm.data.model.ChalkboardList
import com.personalmorningalarm.data.phone.ListHome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each profile may ask for, and how it starts. */
class ProfileTest {

    @Test
    fun `Jess's profile requests none of the alarm's permissions`() {
        AlarmPermission.values().forEach {
            assertFalse("Jess may request $it", Profile.JESS.allows(it))
            assertFalse("Jess may request $it", Profile.allows(Profile.JESS, it))
        }
        assertFalse(Profile.JESS.hasAlarm)
    }

    @Test
    fun `Nick's profile still requests every one of them`() {
        AlarmPermission.values().forEach { assertTrue("Nick may not request $it", Profile.NICK.allows(it)) }
        assertTrue(Profile.NICK.hasAlarm)
    }

    @Test
    fun `before the first-run choice nothing is requested`() {
        AlarmPermission.values().forEach { assertFalse(Profile.allows(null, it)) }
    }

    @Test
    fun `the gated set covers exact alarms, NFC, full-screen intents, battery and notifications`() {
        assertEquals(
            setOf("EXACT_ALARM", "NFC", "FULL_SCREEN_INTENT", "BATTERY_OPTIMISATION", "NOTIFICATIONS"),
            AlarmPermission.values().map { it.name }.toSet()
        )
    }

    @Test
    fun `Nick's new lists go to the hub without asking, Jess is asked starting on the phone`() {
        assertEquals(NewListHome.Always(ListHome.HUB), Profile.NICK.newListHome)
        assertEquals(NewListHome.Ask(ListHome.PHONE), Profile.JESS.newListHome)
    }

    @Test
    fun `Jess's to-do starts on the phone, Nick's is left where it is`() {
        assertEquals(ListHome.PHONE, Profile.JESS.setupTodoHome)
        assertNull(Profile.NICK.setupTodoHome)
    }

    @Test
    fun `each profile names itself to the hub as the hub spells it`() {
        assertEquals("nick", Profile.NICK.wireName)
        assertEquals("jess", Profile.JESS.wireName)
        assertEquals(Profile.JESS, Profile.fromWireName("jess"))
        assertNull(Profile.fromWireName("pending"))
        assertNull(Profile.fromWireName(null))
    }

    @Test
    fun `Nick's Mine to-do follows Settings, Jess's is always her phone list`() {
        assertEquals(ListHome.HUB, Profile.NICK.mineTodoHome(ListHome.HUB))
        assertEquals(ListHome.PHONE, Profile.NICK.mineTodoHome(ListHome.PHONE))
        // The hub's rolling to-do is Nick's own: Jess never gets it, whatever is saved.
        assertEquals(ListHome.PHONE, Profile.JESS.mineTodoHome(ListHome.HUB))
        assertTrue(Profile.NICK.choosesTodoHome)
        assertFalse(Profile.JESS.choosesTodoHome)
    }

    // --- the work list ---

    @Test
    fun `Nick has the work list, as a fourth position on the To-do filter`() {
        assertTrue(Profile.NICK.hasWorkList)
        assertEquals(listOf(TodoSource.FAMILY, TodoSource.MINE, TodoSource.WORK), Profile.NICK.todoSources)
        assertEquals(
            listOf(TodoFilter.ALL, TodoFilter.FAMILY, TodoFilter.MINE, TodoFilter.WORK),
            Profile.NICK.todoFilters
        )
        ChalkboardList.entries.forEach { assertTrue("Nick may not use $it", Profile.NICK.mayUse(it)) }
    }

    @Test
    fun `Jess never has the work list, as a source, a filter position or on the hub`() {
        assertFalse(Profile.JESS.hasWorkList)
        assertEquals(listOf(TodoSource.FAMILY, TodoSource.MINE), Profile.JESS.todoSources)
        assertEquals(listOf(TodoFilter.ALL, TodoFilter.FAMILY, TodoFilter.MINE), Profile.JESS.todoFilters)
        assertFalse(Profile.JESS.mayUse(ChalkboardList.WORK))
        assertFalse(Profile.mayUse(Profile.JESS, ChalkboardList.WORK))
        assertTrue(Profile.JESS.mayUse(ChalkboardList.FAMILY))
    }

    @Test
    fun `before the first-run choice the work list is off limits too`() {
        assertFalse(Profile.mayUse(null, ChalkboardList.WORK))
        assertTrue(Profile.mayUse(null, ChalkboardList.FAMILY))
    }

    @Test
    fun `a saved Work filter opens Nick's screen on Work, and Jess's on All`() {
        assertEquals(TodoFilter.WORK, Profile.NICK.todoFilter(TodoFilter.WORK))
        assertEquals(TodoFilter.ALL, Profile.JESS.todoFilter(TodoFilter.WORK))
        assertEquals(TodoFilter.FAMILY, Profile.JESS.todoFilter(TodoFilter.FAMILY))
        assertEquals(TodoFilter.ALL, Profile.NICK.todoFilter(null))
    }
}
