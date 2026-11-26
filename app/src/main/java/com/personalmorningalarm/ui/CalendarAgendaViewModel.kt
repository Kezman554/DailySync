package com.personalmorningalarm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.model.CalendarEventDto
import com.personalmorningalarm.data.model.CalendarEventsDto
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.CalendarChanges
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The Calendar tile's screen: the Young Family calendar from today for [WEEKS]
 * weeks, in one fetch, cached and falling back like every Alfred feed. [refresh] is
 * for FreshnessWatch, while a saved copy is showing.
 *
 * An event added from here (the Add Event screen) is put in at once and the
 * calendar fetched again, as the Week screen does: a refetch that only finds the
 * saved copy doesn't replace it.
 */
class CalendarAgendaViewModel(
    private val alfred: AlfredRepository,
    added: Flow<CalendarEventDto> = CalendarChanges.added,
    private val today: () -> LocalDate = LocalDate::now
) : ViewModel() {

    data class State(
        /** Null until the first fetch answers. */
        val calendar: AlfredResult<CalendarEventsDto>? = null,
        val from: LocalDate
    )

    private val _state = MutableStateFlow(State(from = today()))
    val state: StateFlow<State> = _state.asStateFlow()

    private var inFlight: Job? = null

    init {
        refresh()
        viewModelScope.launch { added.collect(::eventAdded) }
    }

    /** Ignored while a fetch is running. */
    fun refresh() {
        if (inFlight?.isActive == true) return
        inFlight = viewModelScope.launch { fetch(keepUnlessFresh = false) }
    }

    private suspend fun fetch(keepUnlessFresh: Boolean) {
        val from = today()
        val result = alfred.getCalendarAgenda(from, from.plusWeeks(WEEKS))
        if (keepUnlessFresh && result !is AlfredResult.Fresh) return
        _state.update { it.copy(calendar = result, from = from) }
    }

    private suspend fun eventAdded(event: CalendarEventDto) {
        _state.update { it.copy(calendar = WeekScheduleViewModel.withEvent(it.calendar, event)) }
        // A fetch already in flight may have asked before the event existed: let it
        // land, then put the event back if its answer dropped it.
        inFlight?.join()
        _state.update {
            val shown = (it.calendar as? AlfredResult.Fresh)?.data?.events
                ?: (it.calendar as? AlfredResult.Stale)?.data?.events
            if (shown.orEmpty().contains(event)) it else it.copy(calendar = WeekScheduleViewModel.withEvent(it.calendar, event))
        }
        fetch(keepUnlessFresh = true)
    }

    companion object {
        /** How far ahead the agenda looks. */
        const val WEEKS = 4L
    }
}
