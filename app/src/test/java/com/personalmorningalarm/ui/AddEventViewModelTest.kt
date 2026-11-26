package com.personalmorningalarm.ui

import com.personalmorningalarm.data.model.CalendarCreateRequest
import com.personalmorningalarm.data.model.EventCalendar
import com.personalmorningalarm.data.model.EventProblem
import com.personalmorningalarm.data.model.EventWho
import com.personalmorningalarm.data.model.PhoneEvent
import com.personalmorningalarm.data.phone.PhoneCalendar
import com.personalmorningalarm.data.phone.PhoneCalendarStore
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.remote.CalendarCreateResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The two calendar choices take two paths that never cross: Young Family goes to the
 * hub, exactly once, and My calendar goes to the phone's calendar and nowhere else.
 */
@RunWith(RobolectricTestRunner::class)
class AddEventViewModelTest : ViewModelTestSupport() {

    private val day = LocalDate.of(2026, 11, 25)
    private val personal = PhoneCalendar(1, "Personal", "me@example.com")
    private val work = PhoneCalendar(2, "Work", "me@example.com")
    private val youngFamily = PhoneCalendar(3, "Young Family", "me@example.com")

    private val familyCalls = mutableListOf<CalendarCreateRequest>()
    private var familyAnswer: CalendarCreateResult = CalendarCreateResult.Created(null)
    private var familyGate: CompletableDeferred<Unit>? = null

    private val phone = FakePhoneCalendars()

    private class FakePhoneCalendars : PhoneCalendarStore {
        var calendars = listOf<PhoneCalendar>()
        var remembered: Long? = null
        var accepts = true
        val inserted = mutableListOf<Pair<Long, PhoneEvent>>()
        override fun calendars() = calendars
        override fun insert(calendarId: Long, event: PhoneEvent): Boolean {
            inserted += calendarId to event
            return accepts
        }
        override fun remembered() = remembered
        override fun remember(calendarId: Long) {
            remembered = calendarId
        }
    }

    private fun viewModel(profile: Profile? = Profile.JESS) = track(
        AddEventViewModel(
            createFamilyEvent = { request ->
                familyCalls += request
                familyGate?.await()
                familyAnswer
            },
            phoneCalendars = phone,
            profile = profile,
            initialDate = day,
            now = LocalDateTime.of(day, LocalTime.of(14, 20)),
            phoneZone = ZoneId.of("Europe/London"),
            io = Dispatchers.Unconfined
        )
    )

    private fun AddEventViewModel.fill(title: String = "Dentist", calendar: EventCalendar = EventCalendar.FAMILY) {
        setTitle(title)
        setCalendar(calendar)
    }

    // --- routing ---

    @Test
    fun `Young Family goes to the hub, not the phone`() {
        val vm = viewModel()
        vm.fill()

        vm.save()

        assertEquals(
            listOf(CalendarCreateRequest("Dentist", "jess", start = "2026-11-25T15:00", end = "2026-11-25T16:00")),
            familyCalls
        )
        assertTrue(phone.inserted.isEmpty())
        assertEquals(EventCalendar.FAMILY, vm.state.value.saved)
    }

    @Test
    fun `My calendar goes to the phone, and nothing goes to the hub`() {
        phone.calendars = listOf(personal)
        val vm = viewModel()
        vm.fill(calendar = EventCalendar.MINE)

        vm.save()

        assertTrue(familyCalls.isEmpty())
        assertEquals(listOf(personal.id), phone.inserted.map { it.first })
        assertEquals("Dentist", phone.inserted.single().second.title)
        assertEquals(EventCalendar.MINE, vm.state.value.saved)
    }

    @Test
    fun `the typed prefix comes off for the hub but stays for the phone`() {
        phone.calendars = listOf(personal)
        val vm = viewModel()
        vm.fill(title = "Jess: dentist")
        vm.save()
        assertEquals("dentist", familyCalls.single().title)

        val mine = viewModel()
        mine.fill(title = "Jess: dentist", calendar = EventCalendar.MINE)
        mine.save()
        assertEquals("Jess: dentist", phone.inserted.single().second.title)
    }

    @Test
    fun `Who starts on the profile and goes as chosen`() {
        val vm = viewModel(Profile.NICK)
        assertEquals(EventWho.NICK, vm.state.value.draft.who)
        vm.fill()
        vm.setWho(EventWho.OLIVER)

        vm.save()

        assertEquals("oliver", familyCalls.single().who)
    }

    @Test
    fun `an all-day family event sends the date alone`() {
        val vm = viewModel()
        vm.fill()
        vm.setAllDay(true)

        vm.save()

        assertEquals(CalendarCreateRequest("Dentist", "jess", date = "2026-11-25"), familyCalls.single())
    }

    @Test
    fun `an invalid draft goes nowhere`() {
        phone.calendars = listOf(personal)
        val vm = viewModel()
        vm.fill(title = "  ")
        vm.save()
        vm.setCalendar(EventCalendar.MINE)
        vm.save()

        assertTrue(familyCalls.isEmpty())
        assertTrue(phone.inserted.isEmpty())
        assertEquals(AddEventViewModel.Message.Invalid(EventProblem.BLANK_TITLE), vm.state.value.message)
    }

    // --- a family event that doesn't land: said plainly, kept, never retried ---

    @Test
    fun `unreachable says so, keeps what was typed, and sends nothing more`() {
        familyAnswer = CalendarCreateResult.NotSent
        val vm = viewModel()
        vm.fill(title = "Jess: dentist")

        vm.save()

        val state = vm.state.value
        assertEquals(AddEventViewModel.Message.NotSent, state.message)
        assertEquals("Jess: dentist", state.draft.title)
        assertNull(state.saved)
        assertEquals(false, state.sending)
        assertEquals(1, familyCalls.size)
        assertTrue(phone.inserted.isEmpty())
    }

    @Test
    fun `no answer warns to check the calendar, and trying again is the person's call`() {
        familyAnswer = CalendarCreateResult.Unconfirmed
        val vm = viewModel()
        vm.fill()

        vm.save()
        assertEquals(AddEventViewModel.Message.Unconfirmed, vm.state.value.message)
        assertEquals(1, familyCalls.size)

        // Editing doesn't hide the warning.
        vm.setTitle("Dentist (again?)")
        assertEquals(AddEventViewModel.Message.Unconfirmed, vm.state.value.message)

        familyAnswer = CalendarCreateResult.Created(null)
        vm.save()
        assertEquals(2, familyCalls.size)
        assertEquals(EventCalendar.FAMILY, vm.state.value.saved)
    }

    @Test
    fun `a refusal says why`() {
        familyAnswer = CalendarCreateResult.Refused("end must be after start")
        val vm = viewModel()
        vm.fill()

        vm.save()

        assertEquals(AddEventViewModel.Message.Refused("end must be after start"), vm.state.value.message)
    }

    @Test
    fun `a second tap while sending sends nothing`() {
        familyGate = CompletableDeferred()
        val vm = viewModel()
        vm.fill()

        vm.save()
        assertTrue(vm.state.value.sending)
        vm.save()
        familyGate!!.complete(Unit)
        scheduler.runCurrent()

        assertEquals(1, familyCalls.size)
        assertEquals(EventCalendar.FAMILY, vm.state.value.saved)
    }

    // --- choosing the phone calendar ---

    @Test
    fun `several phone calendars ask which, then remember it and write there`() {
        phone.calendars = listOf(personal, youngFamily, work)
        val vm = viewModel()
        vm.fill(calendar = EventCalendar.MINE)

        vm.save()
        assertEquals(listOf(personal, work), vm.state.value.choosing)
        assertTrue(phone.inserted.isEmpty())

        vm.calendarChosen(work)

        assertNull(vm.state.value.choosing)
        assertEquals(work.id, phone.remembered)
        assertEquals(listOf(work.id), phone.inserted.map { it.first })
        assertTrue(familyCalls.isEmpty())
    }

    @Test
    fun `the remembered calendar is used without asking`() {
        phone.calendars = listOf(personal, work)
        phone.remembered = work.id
        val vm = viewModel()
        vm.fill(calendar = EventCalendar.MINE)

        vm.save()

        assertNull(vm.state.value.choosing)
        assertEquals(listOf(work.id), phone.inserted.map { it.first })
    }

    @Test
    fun `cancelling the question writes nothing and keeps the form`() {
        phone.calendars = listOf(personal, work)
        val vm = viewModel()
        vm.fill(calendar = EventCalendar.MINE)
        vm.save()

        vm.choosingCancelled()

        assertTrue(phone.inserted.isEmpty())
        assertNull(phone.remembered)
        assertEquals("Dentist", vm.state.value.draft.title)
        assertEquals(false, vm.state.value.sending)
    }

    @Test
    fun `changing the phone calendar remembers the new one and writes nothing`() {
        phone.calendars = listOf(personal, work)
        phone.remembered = personal.id
        val vm = viewModel()
        vm.setCalendar(EventCalendar.MINE)

        vm.changePhoneCalendar()
        vm.calendarChosen(work)

        assertEquals(work.id, phone.remembered)
        assertTrue(phone.inserted.isEmpty())
        assertEquals(AddEventViewModel.PhoneTarget.Chosen(work, canChange = true), vm.state.value.phoneTarget)
    }

    @Test
    fun `only Young Family on the phone means no calendar for My calendar`() {
        phone.calendars = listOf(youngFamily)
        val vm = viewModel()
        vm.fill(calendar = EventCalendar.MINE)

        vm.save()

        assertEquals(AddEventViewModel.Message.NoPhoneCalendar, vm.state.value.message)
        assertTrue(phone.inserted.isEmpty())
        assertTrue(familyCalls.isEmpty())
    }

    @Test
    fun `the phone refusing the write says so`() {
        phone.calendars = listOf(personal)
        phone.accepts = false
        val vm = viewModel()
        vm.fill(calendar = EventCalendar.MINE)

        vm.save()

        assertEquals(AddEventViewModel.Message.PhoneWriteFailed, vm.state.value.message)
        assertNull(vm.state.value.saved)
    }

    // --- the form's times ---

    @Test
    fun `the times start on the next whole hour, for an hour`() {
        assertEquals(LocalTime.of(15, 0) to LocalTime.of(16, 0), AddEventViewModel.defaultTimes(LocalTime.of(14, 20)))
        assertEquals(LocalTime.of(23, 0) to LocalTime.of(23, 59), AddEventViewModel.defaultTimes(LocalTime.of(22, 5)))
        assertEquals(LocalTime.of(9, 0) to LocalTime.of(10, 0), AddEventViewModel.defaultTimes(LocalTime.of(23, 30)))
    }

    @Test
    fun `moving the start carries the end with it`() {
        val vm = viewModel()
        vm.setEnd(LocalTime.of(15, 30))

        vm.setStart(LocalTime.of(18, 0))
        assertEquals(LocalTime.of(18, 30), vm.state.value.draft.end)

        vm.setStart(LocalTime.of(23, 45))
        assertEquals(LocalTime.of(23, 59), vm.state.value.draft.end)
    }
}
