package com.personalmorningalarm.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.model.CalendarEventsDto
import com.personalmorningalarm.data.model.FamilyCalendar
import com.personalmorningalarm.data.model.FamilyEvent
import com.personalmorningalarm.data.model.WeekSchedule
import com.personalmorningalarm.data.model.WeekScheduleDto
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Backs the week schedule screen: one fetch of the whole plan week per screen
 * visit (the ViewModel's lifetime), cached and falling back like every other
 * Alfred feed — plus [refresh], which the screen calls only while it's showing
 * a saved copy (FreshnessWatch). Page swipes are pure UI — they never touch the
 * network.
 *
 * The family calendar is fetched separately and layered in when it arrives, in a
 * single call covering the whole visible week rather than one per day. The plan is
 * emitted first and never waits on it: the calendar being absent is a calendar
 * missing from the page, never a blank week.
 */
class WeekScheduleViewModel(private val alfred: AlfredRepository) : ViewModel() {

    /** Null week means "not fetched yet", shown as loading rather than failure. */
    data class WeekState(
        val loading: Boolean = true,
        val week: AlfredResult<WeekScheduleDto>? = null,
        val calendar: AlfredResult<CalendarEventsDto>? = null
    ) {
        /** That week's family events bucketed by day; empty when there's no calendar. */
        val eventsByDate: Map<LocalDate, List<FamilyEvent>>
            get() = FamilyCalendar.byDate(
                when (val result = calendar) {
                    is AlfredResult.Fresh -> result.data
                    is AlfredResult.Stale -> result.data
                    else -> null
                }
            )

    }

    private val _state = MutableStateFlow(WeekState())
    val state: StateFlow<WeekState> = _state.asStateFlow()

    private var inFlight: Job? = null

    init {
        refresh()
    }

    /** Ignored while a fetch is already running. The previous week stays up until the new one lands. */
    fun refresh() {
        if (inFlight?.isActive == true) return
        inFlight = viewModelScope.launch {
            // Never throws — unreachable comes back as Stale or Unavailable.
            val result = alfred.getWeekSchedule()
            _state.value = _state.value.copy(loading = false, week = result)
            Log.d(TAG, "Week schedule fetched: ${result.javaClass.simpleName}")
            fetchCalendarFor(result)
        }
    }

    /**
     * One call for the whole week the pager will show. The endpoint's `end` is
     * exclusive, so it asks for the day after the plan's last day.
     */
    private suspend fun fetchCalendarFor(week: AlfredResult<WeekScheduleDto>) {
        val days = WeekSchedule.days(
            when (week) {
                is AlfredResult.Fresh -> week.data
                is AlfredResult.Stale -> week.data
                AlfredResult.Unavailable -> null
            }
        )
        // No days to render means nothing to layer a calendar onto.
        if (days.isEmpty()) return

        val result = alfred.getCalendarWeek(days.first().date, days.last().date.plusDays(1))
        _state.value = _state.value.copy(calendar = result)
        Log.d(TAG, "Family calendar fetched: ${result.javaClass.simpleName}")
    }

    private companion object {
        const val TAG = "PMA"
    }
}
