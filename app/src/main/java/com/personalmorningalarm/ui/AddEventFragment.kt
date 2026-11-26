package com.personalmorningalarm.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.fragment.findNavController
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.EventCalendar
import com.personalmorningalarm.data.model.EventProblem
import com.personalmorningalarm.data.model.EventWho
import com.personalmorningalarm.data.phone.AndroidPhoneCalendars
import com.personalmorningalarm.data.phone.PhoneCalendar
import com.personalmorningalarm.data.profile.ProfileSettings
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.databinding.FragmentAddEventBinding
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Adds one calendar event, to Young Family through the hub or to a Google calendar
 * on this phone (see [AddEventViewModel] for the rules). Calendar permission is
 * asked for only when My calendar is first chosen, or saved to without it.
 */
class AddEventFragment : Fragment() {

    private var _binding: FragmentAddEventBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AddEventViewModel by viewModels {
        viewModelFactory {
            initializer {
                val context = requireContext().applicationContext
                val repository = AlfredRepository(context)
                AddEventViewModel(
                    createFamilyEvent = repository::createCalendarEvent,
                    phoneCalendars = AndroidPhoneCalendars(context),
                    profile = ProfileSettings(context).profile(),
                    initialDate = requireArguments().getString(ARG_DATE)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                        ?: LocalDate.now()
                )
            }
        }
    }

    /** What to carry on with once calendar permission is answered. */
    private enum class AfterPermission { SHOW_TARGET, SAVE }

    private var afterPermission = AfterPermission.SHOW_TARGET

    private val requestCalendar =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (CALENDAR_PERMISSIONS.all { grants[it] == true || hasPermission(it) }) {
                viewModel.refreshPhoneTarget()
                if (afterPermission == AfterPermission.SAVE) viewModel.save()
            } else {
                viewModel.permissionDenied()
            }
        }

    private var chooser: AlertDialog? = null

    /** The message last shown, so a new one scrolls into view once, not on every render. */
    private var shownMessage: AddEventViewModel.Message? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAddEventBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindScreenTitle(binding.titleBar, R.string.event_title_screen)

        binding.etTitle.setText(viewModel.state.value.draft.title)
        binding.etTitle.doAfterTextChanged { viewModel.setTitle(it?.toString().orEmpty()) }

        binding.segFamily.setOnClickListener { viewModel.setCalendar(EventCalendar.FAMILY) }
        binding.segMine.setOnClickListener {
            viewModel.setCalendar(EventCalendar.MINE)
            withCalendarPermission(AfterPermission.SHOW_TARGET) { viewModel.refreshPhoneTarget() }
        }
        whoSegments().forEach { (who, segment) -> segment.setOnClickListener { viewModel.setWho(who) } }
        binding.tvCalendarHint.setOnClickListener {
            val target = viewModel.state.value.phoneTarget
            if (viewModel.state.value.draft.calendar == EventCalendar.MINE &&
                target is AddEventViewModel.PhoneTarget.Chosen && target.canChange
            ) {
                viewModel.changePhoneCalendar()
            }
        }

        binding.rowDate.setOnClickListener { pickDate() }
        binding.rowStart.setOnClickListener { pickTime(TAG_START, viewModel.state.value.draft.start) }
        binding.rowEnd.setOnClickListener { pickTime(TAG_END, viewModel.state.value.draft.end) }
        binding.switchAllDay.setOnCheckedChangeListener { _, checked -> viewModel.setAllDay(checked) }
        reattachPickers()

        binding.btnSaveEvent.setOnClickListener { save() }

        if (viewModel.state.value.draft.calendar == EventCalendar.MINE && hasCalendarPermission()) {
            viewModel.refreshPhoneTarget()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun save() {
        if (viewModel.state.value.draft.calendar == EventCalendar.MINE) {
            withCalendarPermission(AfterPermission.SAVE) { viewModel.save() }
        } else {
            viewModel.save()
        }
    }

    private fun render(state: AddEventViewModel.State) {
        state.saved?.let { calendar ->
            viewModel.savedHandled()
            Toast.makeText(
                requireContext(),
                if (calendar == EventCalendar.FAMILY) R.string.event_saved_family else R.string.event_saved_mine,
                Toast.LENGTH_SHORT
            ).show()
            findNavController().navigateUp()
            return
        }
        val draft = state.draft
        val family = draft.calendar == EventCalendar.FAMILY
        binding.segFamily.isSelected = family
        binding.segMine.isSelected = !family
        binding.groupWho.isVisible = family
        whoSegments().forEach { (who, segment) -> segment.isSelected = who == draft.who }
        binding.tvCalendarHint.text = calendarHint(state)

        binding.tvDate.text = draft.date.format(DATE_FORMAT)
        if (binding.switchAllDay.isChecked != draft.allDay) binding.switchAllDay.isChecked = draft.allDay
        binding.groupTimes.isVisible = !draft.allDay
        binding.tvStart.text = draft.start.format(TIME_FORMAT)
        binding.tvEnd.text = draft.end.format(TIME_FORMAT)

        binding.tvEventMessage.isVisible = state.message != null
        binding.tvEventMessage.text = state.message?.let(::messageText).orEmpty()
        if (state.message != null && state.message != shownMessage) {
            // The message pushes the button down: bring both into view, so trying
            // again doesn't mean hunting for it.
            binding.root.post { _binding?.let { it.root.smoothScrollTo(0, it.btnSaveEvent.bottom) } }
        }
        shownMessage = state.message

        binding.btnSaveEvent.isEnabled = !state.sending
        binding.btnSaveEvent.setText(if (state.sending) R.string.event_saving else R.string.event_save)

        renderChooser(state.choosing)
    }

    private fun calendarHint(state: AddEventViewModel.State): String {
        if (state.draft.calendar == EventCalendar.FAMILY) return getString(R.string.event_hint_family)
        return when (val target = state.phoneTarget) {
            AddEventViewModel.PhoneTarget.Unknown -> getString(R.string.event_hint_mine_unknown)
            is AddEventViewModel.PhoneTarget.Chosen -> getString(
                if (target.canChange) R.string.event_hint_mine_change else R.string.event_hint_mine_chosen,
                target.calendar.name
            )
            AddEventViewModel.PhoneTarget.WillAsk -> getString(R.string.event_hint_mine_ask)
            AddEventViewModel.PhoneTarget.NoneFound -> getString(R.string.event_hint_mine_none)
        }
    }

    private fun messageText(message: AddEventViewModel.Message): String = when (message) {
        is AddEventViewModel.Message.Invalid -> getString(
            when (message.problem) {
                EventProblem.BLANK_TITLE -> R.string.event_problem_blank
                EventProblem.TITLE_TOO_LONG -> R.string.event_problem_too_long
                EventProblem.TITLE_NOT_ONE_LINE -> R.string.event_problem_one_line
                EventProblem.END_NOT_AFTER_START -> R.string.event_problem_end
                EventProblem.TIME_SKIPPED -> R.string.event_problem_skipped
            }
        )
        is AddEventViewModel.Message.Refused ->
            message.reason?.let { getString(R.string.event_refused, it) } ?: getString(R.string.event_refused_plain)
        AddEventViewModel.Message.NotSent -> getString(R.string.event_not_sent)
        AddEventViewModel.Message.Unconfirmed -> getString(R.string.event_unconfirmed)
        AddEventViewModel.Message.NoPhoneCalendar -> getString(R.string.event_no_phone_calendar)
        AddEventViewModel.Message.PhoneWriteFailed -> getString(R.string.event_phone_failed)
        AddEventViewModel.Message.NeedsCalendarPermission -> getString(R.string.event_needs_permission)
    }

    /** The "which calendar?" question, shown while the ViewModel is asking it. */
    private fun renderChooser(choosing: List<PhoneCalendar>?) {
        if (choosing == null) {
            chooser?.let { it.setOnDismissListener(null); it.dismiss() }
            chooser = null
            return
        }
        if (chooser?.isShowing == true) return
        // A primary calendar is named after its account: say it once.
        val labels = choosing.map { if (it.name == it.account) it.name else "${it.name}  ·  ${it.account}" }
            .toTypedArray()
        chooser = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.event_pick_calendar)
            .setItems(labels) { _, which -> viewModel.calendarChosen(choosing[which]) }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { if (viewModel.state.value.choosing != null) viewModel.choosingCancelled() }
            .show()
    }

    private fun whoSegments() = listOf(
        EventWho.NICK to binding.segWhoNick,
        EventWho.JESS to binding.segWhoJess,
        EventWho.OLIVER to binding.segWhoOliver,
        EventWho.FAMILY to binding.segWhoFamily
    )

    private fun pickDate() {
        if (childFragmentManager.findFragmentByTag(TAG_DATE) != null) return
        val picker = MaterialDatePicker.Builder.datePicker()
            .setSelection(viewModel.state.value.draft.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            .build()
        attachDate(picker)
        picker.show(childFragmentManager, TAG_DATE)
    }

    private fun attachDate(picker: MaterialDatePicker<Long>) {
        picker.addOnPositiveButtonClickListener { millis ->
            viewModel.setDate(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
        }
    }

    private fun pickTime(tag: String, current: LocalTime) {
        if (childFragmentManager.findFragmentByTag(tag) != null) return
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(current.hour)
            .setMinute(current.minute)
            .build()
        attachTime(tag, picker)
        picker.show(childFragmentManager, tag)
    }

    private fun attachTime(tag: String, picker: MaterialTimePicker) {
        picker.addOnPositiveButtonClickListener {
            val time = LocalTime.of(picker.hour, picker.minute)
            if (tag == TAG_START) viewModel.setStart(time) else viewModel.setEnd(time)
        }
    }

    /** A picker left open across a rotation comes back without its listener: give it one. */
    @Suppress("UNCHECKED_CAST")
    private fun reattachPickers() {
        (childFragmentManager.findFragmentByTag(TAG_DATE) as? MaterialDatePicker<Long>)?.let(::attachDate)
        listOf(TAG_START, TAG_END).forEach { tag ->
            (childFragmentManager.findFragmentByTag(tag) as? MaterialTimePicker)?.let { attachTime(tag, it) }
        }
    }

    private fun withCalendarPermission(after: AfterPermission, granted: () -> Unit) {
        if (hasCalendarPermission()) {
            granted()
        } else {
            afterPermission = after
            requestCalendar.launch(CALENDAR_PERMISSIONS)
        }
    }

    private fun hasCalendarPermission() = CALENDAR_PERMISSIONS.all(::hasPermission)

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED

    override fun onDestroyView() {
        super.onDestroyView()
        // Not a cancel: the question comes back with the view.
        chooser?.let { it.setOnDismissListener(null); it.dismiss() }
        chooser = null
        shownMessage = null
        _binding = null
    }

    companion object {
        const val ARG_DATE = "date"

        private val CALENDAR_PERMISSIONS =
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        private const val TAG_DATE = "event_date"
        private const val TAG_START = "event_start"
        private const val TAG_END = "event_end"

        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMMM", Locale.getDefault())
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    }
}
