package com.personalmorningalarm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.model.CalendarCreateRequest
import com.personalmorningalarm.data.model.EventCalendar
import com.personalmorningalarm.data.model.EventDraft
import com.personalmorningalarm.data.model.EventProblem
import com.personalmorningalarm.data.model.EventValidation
import com.personalmorningalarm.data.model.EventWho
import com.personalmorningalarm.data.model.NewCalendarEvent
import com.personalmorningalarm.data.model.PhoneEvent
import com.personalmorningalarm.data.phone.PhoneCalendar
import com.personalmorningalarm.data.phone.PhoneCalendarChoice
import com.personalmorningalarm.data.phone.PhoneCalendarPick
import com.personalmorningalarm.data.phone.PhoneCalendarStore
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.remote.CalendarCreateResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The Add Event screen. Reached from the Week screen's add button (and, later, Jess's
 * Calendar tile), so it knows nothing about where it was opened from.
 *
 * Two paths, decided by [EventCalendar] alone:
 *  - Young Family goes to the hub, once. It is never queued and never retried
 *    automatically, because every POST makes an event. If it doesn't land, what was
 *    typed stays put and the person decides whether to send it again.
 *  - My calendar goes to a Google calendar on this phone through CalendarContract,
 *    and nothing about it reaches the Pi.
 */
class AddEventViewModel(
    private val createFamilyEvent: suspend (CalendarCreateRequest) -> CalendarCreateResult,
    private val phoneCalendars: PhoneCalendarStore,
    profile: Profile?,
    initialDate: LocalDate,
    now: LocalDateTime = LocalDateTime.now(),
    private val phoneZone: ZoneId = ZoneId.systemDefault(),
    private val io: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    /** What the screen says under the form. */
    sealed interface Message {
        data class Invalid(val problem: EventProblem) : Message
        /** The hub refused it; [reason] is its own words when it gave any. */
        data class Refused(val reason: String?) : Message
        /** The hub couldn't be reached: nothing was sent. */
        data object NotSent : Message
        /** Sent, no answer: it may be in. Check before trying again. */
        data object Unconfirmed : Message
        data object NoPhoneCalendar : Message
        data object PhoneWriteFailed : Message
        data object NeedsCalendarPermission : Message
    }

    /** Which phone calendar "My calendar" will use, for the line under the choice. */
    sealed interface PhoneTarget {
        /** Not looked yet (no permission, or Young Family chosen). */
        data object Unknown : PhoneTarget
        data class Chosen(val calendar: PhoneCalendar, val canChange: Boolean) : PhoneTarget
        /** Several to choose from and none chosen: asked on save. */
        data object WillAsk : PhoneTarget
        data object NoneFound : PhoneTarget
    }

    data class State(
        val draft: EventDraft,
        val sending: Boolean = false,
        val message: Message? = null,
        val phoneTarget: PhoneTarget = PhoneTarget.Unknown,
        /** Non-null while the person is being asked which phone calendar to use. */
        val choosing: List<PhoneCalendar>? = null,
        /** Set once the event is in; the screen says so and closes. */
        val saved: EventCalendar? = null
    )

    private val _state: MutableStateFlow<State>

    init {
        val (start, end) = defaultTimes(now.toLocalTime())
        _state = MutableStateFlow(
            State(
                EventDraft(
                    title = "",
                    date = initialDate,
                    allDay = false,
                    start = start,
                    end = end,
                    calendar = EventCalendar.FAMILY,
                    who = EventWho.defaultFor(profile)
                )
            )
        )
    }

    val state: StateFlow<State> = _state.asStateFlow()

    /** The phone event waiting on the person's choice of calendar. */
    private var pendingPhoneEvent: PhoneEvent? = null

    fun setTitle(title: String) = editDraft { it.copy(title = title) }

    fun setDate(date: LocalDate) = editDraft { it.copy(date = date) }

    fun setAllDay(allDay: Boolean) = editDraft { it.copy(allDay = allDay) }

    fun setWho(who: EventWho) = editDraft { it.copy(who = who) }

    /** Moving the start carries the end with it, keeping the length, so it stays after the start. */
    fun setStart(start: LocalTime) = editDraft { draft ->
        val length = Duration.between(draft.start, draft.end).takeIf { !it.isNegative && !it.isZero }
            ?: Duration.ofHours(1)
        val end = start.plus(length)
        draft.copy(start = start, end = if (end.isAfter(start)) end else LAST_MINUTE)
    }

    fun setEnd(end: LocalTime) = editDraft { it.copy(end = end) }

    fun setCalendar(calendar: EventCalendar) = editDraft { it.copy(calendar = calendar) }

    /**
     * Looks up which phone calendar "My calendar" will use. The screen calls it once
     * calendar permission is held, so this never asks for it.
     */
    fun refreshPhoneTarget() {
        viewModelScope.launch {
            val (calendars, remembered) = withContext(io) { phoneCalendars.calendars() to phoneCalendars.remembered() }
            _state.update { it.copy(phoneTarget = targetFor(calendars, remembered)) }
        }
    }

    /** "Change" on the phone calendar line: ask again, with nothing waiting to be written. */
    fun changePhoneCalendar() {
        viewModelScope.launch {
            val offered = withContext(io) { PhoneCalendarChoice.offered(phoneCalendars.calendars()) }
            pendingPhoneEvent = null
            _state.update { it.copy(choosing = offered.takeIf { list -> list.size > 1 }) }
        }
    }

    fun permissionDenied() {
        _state.update { it.copy(message = Message.NeedsCalendarPermission) }
    }

    /**
     * Checks the draft and sends it down its calendar's path. Ignored while a send is
     * in flight, so a double tap can't make two family events.
     */
    fun save() {
        val current = _state.value
        if (current.sending) return
        when (val checked = NewCalendarEvent.validate(current.draft, phoneZone)) {
            is EventValidation.Invalid -> _state.update { it.copy(message = Message.Invalid(checked.problem)) }
            is EventValidation.Family -> sendFamily(checked.request)
            is EventValidation.Mine -> saveMine(checked.event)
        }
    }

    private fun sendFamily(request: CalendarCreateRequest) {
        _state.update { it.copy(sending = true, message = null) }
        viewModelScope.launch {
            // Exactly one call, whatever comes back.
            val result = createFamilyEvent(request)
            _state.update {
                when (result) {
                    is CalendarCreateResult.Created -> it.copy(sending = false, saved = EventCalendar.FAMILY)
                    is CalendarCreateResult.Refused -> it.copy(sending = false, message = Message.Refused(result.reason))
                    CalendarCreateResult.NotSent -> it.copy(sending = false, message = Message.NotSent)
                    CalendarCreateResult.Unconfirmed -> it.copy(sending = false, message = Message.Unconfirmed)
                }
            }
        }
    }

    private fun saveMine(event: PhoneEvent) {
        _state.update { it.copy(sending = true, message = null) }
        viewModelScope.launch {
            val (calendars, remembered) = withContext(io) { phoneCalendars.calendars() to phoneCalendars.remembered() }
            when (val pick = PhoneCalendarChoice.resolve(calendars, remembered)) {
                is PhoneCalendarPick.Use -> writePhone(pick.calendar, event)
                is PhoneCalendarPick.Ask -> {
                    pendingPhoneEvent = event
                    _state.update { it.copy(sending = false, choosing = pick.calendars) }
                }
                PhoneCalendarPick.None -> _state.update {
                    it.copy(sending = false, message = Message.NoPhoneCalendar, phoneTarget = PhoneTarget.NoneFound)
                }
            }
        }
    }

    /** The person picked [calendar]: remember it, and write the event that was waiting, if any. */
    fun calendarChosen(calendar: PhoneCalendar) {
        val event = pendingPhoneEvent
        pendingPhoneEvent = null
        _state.update { it.copy(choosing = null) }
        viewModelScope.launch {
            withContext(io) { phoneCalendars.remember(calendar.id) }
            _state.update { it.copy(phoneTarget = PhoneTarget.Chosen(calendar, canChange = true)) }
            if (event != null) {
                _state.update { it.copy(sending = true) }
                writePhone(calendar, event)
            }
        }
    }

    /** The screen has said it's saved and is closing: don't say it again after a rotation. */
    fun savedHandled() {
        _state.update { it.copy(saved = null) }
    }

    fun choosingCancelled() {
        pendingPhoneEvent = null
        _state.update { it.copy(choosing = null) }
    }

    private suspend fun writePhone(calendar: PhoneCalendar, event: PhoneEvent) {
        val written = withContext(io) { phoneCalendars.insert(calendar.id, event) }
        _state.update {
            if (written) it.copy(sending = false, saved = EventCalendar.MINE)
            else it.copy(sending = false, message = Message.PhoneWriteFailed)
        }
    }

    private fun targetFor(calendars: List<PhoneCalendar>, remembered: Long?): PhoneTarget {
        val canChange = PhoneCalendarChoice.offered(calendars).size > 1
        return when (val pick = PhoneCalendarChoice.resolve(calendars, remembered)) {
            is PhoneCalendarPick.Use -> PhoneTarget.Chosen(pick.calendar, canChange)
            is PhoneCalendarPick.Ask -> PhoneTarget.WillAsk
            PhoneCalendarPick.None -> PhoneTarget.NoneFound
        }
    }

    /** An edit clears a complaint about the form, but never the warning about an unconfirmed send. */
    private inline fun editDraft(change: (EventDraft) -> EventDraft) {
        _state.update { state ->
            val message = state.message.takeUnless { it is Message.Invalid || it is Message.NeedsCalendarPermission }
            state.copy(draft = change(state.draft), message = message)
        }
    }

    companion object {
        private val LAST_MINUTE: LocalTime = LocalTime.of(23, 59)
        private val MORNING: LocalTime = LocalTime.of(9, 0)

        /**
         * The next whole hour for an hour, or 09:00 if that would run into tomorrow.
         * The end is clamped to 23:59 so it's always after the start on the same day.
         */
        fun defaultTimes(now: LocalTime): Pair<LocalTime, LocalTime> {
            val nextHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
            val start = if (nextHour.isBefore(now)) MORNING else nextHour
            val end = start.plusHours(1).let { if (it.isAfter(start)) it else LAST_MINUTE }
            return start to end
        }
    }
}
