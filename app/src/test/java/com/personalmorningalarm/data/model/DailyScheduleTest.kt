package com.personalmorningalarm.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grouping of Alfred's flat /daily-schedule array into display order. */
class DailyScheduleTest {

    private fun task(task: String?, period: String? = null) = ScheduleTaskDto(task, period)

    @Test
    fun `groups by period in display order regardless of response order`() {
        val groups = DailySchedule.group(
            listOf(
                task("Dinner", "eve"),
                task("Gym", "am"),
                task("Standup", "pm"),
                task("Read", null),
                task("Run", "early")
            )
        )

        assertEquals(
            listOf(
                SchedulePeriod.EARLY,
                SchedulePeriod.MORNING,
                SchedulePeriod.AFTERNOON,
                SchedulePeriod.EVENING,
                SchedulePeriod.UNGROUPED
            ),
            groups.map { it.period }
        )
    }

    @Test
    fun `preserves API order within a group`() {
        val groups = DailySchedule.group(
            listOf(
                task("Coffee", "am"),
                task("Gym", "am"),
                task("Emails", "am")
            )
        )

        assertEquals(listOf("Coffee", "Gym", "Emails"), groups.single().tasks)
    }

    @Test
    fun `omits periods with no tasks`() {
        val groups = DailySchedule.group(listOf(task("Gym", "am")))

        assertEquals(1, groups.size)
        assertEquals(SchedulePeriod.MORNING, groups.single().period)
    }

    @Test
    fun `early items get their own group ahead of Morning`() {
        val groups = DailySchedule.group(listOf(task("Gym", "am"), task("Run", "early")))

        assertEquals(listOf(SchedulePeriod.EARLY, SchedulePeriod.MORNING), groups.map { it.period })
        assertEquals(listOf("Run"), groups.first().tasks)
    }

    @Test
    fun `only the periods in use appear, still in day order`() {
        val groups = DailySchedule.group(listOf(task("Read", null), task("Cubs", "eve"), task("Run", "early")))

        assertEquals(
            listOf(SchedulePeriod.EARLY, SchedulePeriod.EVENING, SchedulePeriod.UNGROUPED),
            groups.map { it.period }
        )
    }

    @Test
    fun `tasks with no period land in the ungrouped section`() {
        val groups = DailySchedule.group(listOf(task("Read", null)))

        assertEquals(SchedulePeriod.UNGROUPED, groups.single().period)
        assertEquals(listOf("Read"), groups.single().tasks)
    }

    @Test
    fun `an unknown period is ungrouped rather than dropped`() {
        val groups = DailySchedule.group(listOf(task("Brunch", "night")))

        assertEquals(SchedulePeriod.UNGROUPED, groups.single().period)
        assertEquals(listOf("Brunch"), groups.single().tasks)
    }

    @Test
    fun `period matching tolerates case and padding`() {
        val groups = DailySchedule.group(listOf(task("Gym", " AM ")))

        assertEquals(SchedulePeriod.MORNING, groups.single().period)
    }

    @Test
    fun `drops entries with no task text`() {
        val groups = DailySchedule.group(
            listOf(task(null, "am"), task("   ", "am"), task("Gym", "am"))
        )

        assertEquals(listOf("Gym"), groups.single().tasks)
    }

    @Test
    fun `an empty response produces no groups`() {
        assertTrue(DailySchedule.group(emptyList()).isEmpty())
    }
}
