package com.personalmorningalarm.data.remote

import com.personalmorningalarm.data.model.CalendarEventDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Young Family events this app has just added, for any screen showing the calendar
 * to put them in without waiting for a refresh. In-process only: the Week screen
 * sits under the Add Event screen while it's open, so it is listening when one
 * lands.
 */
object CalendarChanges {

    private val _added = MutableSharedFlow<CalendarEventDto>(extraBufferCapacity = 8)
    val added: SharedFlow<CalendarEventDto> = _added.asSharedFlow()

    fun announce(event: CalendarEventDto) {
        _added.tryEmit(event)
    }
}
