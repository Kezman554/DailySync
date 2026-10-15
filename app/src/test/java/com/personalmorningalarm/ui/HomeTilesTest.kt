package com.personalmorningalarm.ui

import com.personalmorningalarm.data.entity.AlarmConfig
import com.personalmorningalarm.data.model.AgendaEntry
import com.personalmorningalarm.data.model.AgendaGroup
import com.personalmorningalarm.data.model.DayAgenda
import com.personalmorningalarm.data.model.FamilyCalendar
import com.personalmorningalarm.data.model.RollingTodoItem
import com.personalmorningalarm.data.model.ScheduleTaskDto
import com.personalmorningalarm.data.model.SchedulePeriod
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class HomeTilesTest {

    private val day = LocalDate.of(2026, 10, 15)

    // --- Next alarm ---

    @Test
    fun alarmOff_hasNoNextAlarm() {
        assertNull(HomeTiles.nextAlarm(AlarmConfig(alarmTime = 390, isEnabled = false), day.atTime(5, 0)))
        assertNull(HomeTiles.nextAlarm(null, day.atTime(5, 0)))
    }

    @Test
    fun alarmStillAheadToday_isToday() {
        val next = HomeTiles.nextAlarm(AlarmConfig(alarmTime = 390), day.atTime(5, 59))!!
        assertEquals(LocalTime.of(6, 30), next.time)
        assertFalse(next.tomorrow)
    }

    @Test
    fun alarmAlreadyPassed_orExactlyNow_isTomorrow() {
        assertTrue(HomeTiles.nextAlarm(AlarmConfig(alarmTime = 390), day.atTime(14, 0))!!.tomorrow)
        assertTrue(HomeTiles.nextAlarm(AlarmConfig(alarmTime = 390), day.atTime(6, 30))!!.tomorrow)
    }

    // --- Schedule strip ---

    private fun agenda(vararg tasks: Pair<String, String?>): DayAgenda =
        FamilyCalendar.agenda(tasks.map { ScheduleTaskDto(it.first, it.second) }, emptyList())

    @Test
    fun columns_followPeriodOrder_withAnytimeLast_andSkipEmptyPeriods() {
        val columns = HomeTiles.scheduleColumns(
            agenda("Call the garage" to null, "Cubs" to "eve", "Brisk walk" to "am", "Stretch" to "early"),
            LocalTime.of(10, 0)
        )
        assertEquals(
            listOf(SchedulePeriod.EARLY, SchedulePeriod.MORNING, SchedulePeriod.EVENING, SchedulePeriod.UNGROUPED),
            columns.map { it.period }
        )
        assertEquals(listOf("Call the garage"), columns.last().entries)
    }

    @Test
    fun currentColumn_isThePeriodNowFallsIn() {
        val tasks = arrayOf("Walk" to "am", "Shoulders" to "pm", "Cubs" to "eve")
        fun current(at: LocalTime) =
            HomeTiles.scheduleColumns(agenda(*tasks), at).filter { it.current }.map { it.period }

        assertEquals(listOf(SchedulePeriod.MORNING), current(LocalTime.of(11, 59)))
        assertEquals(listOf(SchedulePeriod.AFTERNOON), current(LocalTime.of(12, 0)))
        assertEquals(listOf(SchedulePeriod.EVENING), current(LocalTime.of(17, 0)))
        // Early has no column today, so nothing is highlighted before 09:00.
        assertEquals(emptyList<SchedulePeriod>(), current(LocalTime.of(8, 0)))
    }

    @Test
    fun anytime_isNeverCurrent() {
        val columns = HomeTiles.scheduleColumns(agenda("Call the garage" to null), LocalTime.of(23, 0))
        assertFalse(columns.single().current)
    }

    @Test
    fun calendarEvents_showTheirStartTime() {
        val agenda = DayAgenda(
            allDay = emptyList(),
            groups = listOf(
                AgendaGroup(
                    SchedulePeriod.EVENING,
                    listOf(
                        AgendaEntry("Cubs", LocalTime.of(17, 30), fromCalendar = true),
                        AgendaEntry("Shoulders", null, fromCalendar = false)
                    )
                )
            )
        )
        assertEquals(
            listOf("17:30 Cubs", "Shoulders"),
            HomeTiles.scheduleColumns(agenda, LocalTime.NOON).single().entries
        )
    }

    // --- To-do and shopping ---

    @Test
    fun openTodos_skipsTicked_andTakesThree() {
        val items = listOf(
            RollingTodoItem("a", null), RollingTodoItem("ticked", null, done = true),
            RollingTodoItem("b", null), RollingTodoItem("c", null), RollingTodoItem("d", null)
        )
        assertEquals(listOf("a", "b", "c"), HomeTiles.openTodos(items).map { it.task })
    }

    @Test
    fun activeShoppingList_isTheFirstWithItemsLeft() {
        val lists = listOf(
            ShoppingListSummaryDto("done.md", "Done", total = 4, unticked = 0),
            ShoppingListSummaryDto("untitled.md", " ", total = 2, unticked = 2),
            ShoppingListSummaryDto("tech.md", "Alfred tech", total = 12, unticked = 9),
            ShoppingListSummaryDto("food.md", "Food", total = 3, unticked = 3)
        )
        assertEquals("Alfred tech", HomeTiles.activeShoppingList(lists)?.title)
        assertNull(HomeTiles.activeShoppingList(lists.take(2)))
    }
}
