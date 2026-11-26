package com.personalmorningalarm.challenge

import com.personalmorningalarm.data.model.ChalkboardList
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.RollingTodo
import com.personalmorningalarm.data.model.RollingTodoItem
import com.personalmorningalarm.data.model.WorkDayDto
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.dataOrNull
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The alarm's Work step: Nick's open work items, shown straight after the rolling
 * to-do step on a work day. Read-only; ticking stays on the To-Do screen.
 *
 * The step shows only when all of these hold: the profile has the work list
 * ([Profile.hasWorkList]: never Jess's), today is a work day, and the work list
 * has something open. Otherwise it is skipped, never shown empty.
 */
object WorkStep {

    /**
     * Whether [today] is a work day. The hub's answer counts only when it is about
     * [today]: a saved copy from another day says nothing about this one. With no
     * such answer (the Pi unreachable and nothing saved today), Monday to Friday.
     */
    fun isWorkDay(answer: AlfredResult<WorkDayDto>?, today: LocalDate): Boolean {
        val dto = answer?.dataOrNull()
        val workDay = dto?.workDay
        if (workDay != null && dto.date == today.toString()) return workDay
        return today.dayOfWeek != DayOfWeek.SATURDAY && today.dayOfWeek != DayOfWeek.SUNDAY
    }

    /**
     * The open items the step lists, from the work list as the hub (or its saved
     * copy) has it, markup resolved by [RollingTodo.items] as on every list. Null,
     * meaning skip the step, when the list can't be had or has nothing open.
     */
    fun openItems(list: AlfredResult<List<ChalkboardTaskDto>>?): List<RollingTodoItem>? =
        list?.dataOrNull()
            ?.let { RollingTodo.items(it).filterNot(RollingTodoItem::done) }
            ?.takeIf { it.isNotEmpty() }

    /** What the step shows: its items, and the list's answer for the saved-copy age. */
    data class Content(
        val items: List<RollingTodoItem>,
        val list: AlfredResult<List<ChalkboardTaskDto>>
    )

    /**
     * Decides the step for this morning, asking the hub only what it must: nothing
     * at all for a profile without the work list, and the list itself only on a
     * work day. Null means skip. Never throws: the repository doesn't.
     */
    suspend fun load(alfred: AlfredRepository, profile: Profile?, today: LocalDate): Content? {
        if (profile?.hasWorkList != true) return null
        if (!isWorkDay(alfred.getWorkDay(), today)) return null
        val list = alfred.getChalkboard(ChalkboardList.WORK)
        return openItems(list)?.let { Content(it, list) }
    }
}
