package com.personalmorningalarm.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.core.text.inSpans
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR
import com.personalmorningalarm.R
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.entity.AlarmConfig
import com.personalmorningalarm.data.entity.ContentToggle
import com.personalmorningalarm.data.model.AlarmSounds
import com.personalmorningalarm.data.model.ContentType
import com.personalmorningalarm.data.model.MorningGoal
import com.personalmorningalarm.databinding.FragmentHomeBinding
import com.personalmorningalarm.databinding.ItemAlarmTileBinding
import com.personalmorningalarm.databinding.ItemContentToggleBinding
import com.personalmorningalarm.service.CountdownService
import com.personalmorningalarm.util.AlarmScheduler
import com.personalmorningalarm.util.PinManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Alarm tab: the time and on/off switch with today's state and streak, the
 * morning goal, a tile per alarm setting and the dev tools. Fits one screen.
 *
 * The small tiles open Settings at their section until session AK gives them
 * screens of their own; the morning content items toggle in place.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels {
        ViewModelFactory(AlarmRepository(AppDatabase.getInstance(requireContext())))
    }

    private lateinit var scheduler: AlarmScheduler
    private lateinit var pinManager: PinManager

    /** The tick grid, in display order: Quote, Stretch / Schedule, To-do. */
    private val contentItems: List<Pair<ContentType, ItemContentToggleBinding>>
        get() = listOf(
            ContentType.QUOTE to binding.itemQuote,
            ContentType.STRETCH to binding.itemStretch,
            ContentType.DAILY_SCHEDULE to binding.itemSchedule,
            ContentType.CHALKBOARD to binding.itemTodo
        )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scheduler = AlarmScheduler(requireContext())
        pinManager = PinManager(requireContext())

        binding.tvAlarmTime.setOnClickListener {
            PinPrompts.guard(requireContext(), pinManager) { showTimePicker() }
        }

        // setOnClickListener fires only on user taps (after the checked state
        // flips), so programmatic state updates below don't loop back.
        binding.switchEnabled.setOnClickListener { onToggleEnabledTapped() }
        binding.pillExercise.setOnClickListener { viewModel.setMorningGoal(MorningGoal.EXERCISE) }
        binding.pillProject.setOnClickListener { viewModel.setMorningGoal(MorningGoal.PROJECT) }

        setUpTile(binding.tileSound, R.drawable.ic_badge_sound, R.string.alarm_tab_sound, SettingsFragment.SECTION_SOUND)
        setUpTile(binding.tileNfc, R.drawable.ic_badge_nfc, R.string.alarm_tab_nfc, SettingsFragment.SECTION_NFC)
        setUpTile(binding.tileNuclear, R.drawable.ic_badge_nuclear, R.string.alarm_tab_nuclear, SettingsFragment.SECTION_SOUND)
        setUpTile(binding.tilePin, R.drawable.ic_badge_pin, R.string.alarm_tab_pin, SettingsFragment.SECTION_PIN)
        binding.rowContentHeader.setOnClickListener { openSettings(SettingsFragment.SECTION_CONTENT) }

        setupDevTools()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.config, viewModel.stats) { config, stats -> config to stats }
                        .collect { (config, stats) -> renderConfig(config, stats.currentStreak) }
                }
                launch { viewModel.contentToggles.collect(::renderContent) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The PIN is set and cleared in Settings; re-read it on the way back.
        binding.tilePin.tvValue.setText(
            if (pinManager.isPinSet()) R.string.alarm_tab_pin_on else R.string.alarm_tab_pin_off
        )
    }

    // --- Rendering ---

    private fun renderConfig(config: AlarmConfig?, streak: Int) {
        val minutes = config?.alarmTime ?: HomeViewModel.DEFAULT_ALARM_MINUTES
        val enabled = config?.isEnabled == true

        binding.tvAlarmTime.text = formatTime(minutes)
        binding.switchEnabled.isChecked = enabled

        // "● On" in positive when armed, "○ Off" in the line's secondary text when
        // not; the streak follows either way.
        val streakText = resources.getQuantityString(R.plurals.alarm_tab_streak, streak, streak)
        binding.tvAlarmStatus.text = SpannableStringBuilder().apply {
            if (enabled) {
                inSpans(ForegroundColorSpan(color(R.attr.colorPositive))) {
                    append(getString(R.string.alarm_tab_on))
                }
            } else {
                append(getString(R.string.alarm_tab_off))
            }
            append(streakText)
        }

        val goal = config?.morningGoal ?: MorningGoal.EXERCISE
        binding.pillExercise.isSelected = goal == MorningGoal.EXERCISE
        binding.pillProject.isSelected = goal == MorningGoal.PROJECT

        val stage1 = AlarmSounds.stage1ByKey(config?.stage1SoundId)
        binding.tileSound.tvValue.text = stage1.displayName
        binding.tileSound.tvValueSuffix.visibility = View.VISIBLE
        binding.tileSound.tvValueSuffix.text =
            getString(R.string.alarm_tab_sound_volume, (config?.stage1Volume ?: 100).coerceIn(0, 100))

        val taps = config?.sequenceLength ?: DEFAULT_SEQUENCE_LENGTH
        binding.tileNfc.tvValue.text = resources.getQuantityString(
            R.plurals.alarm_tab_nfc_taps,
            taps,
            taps,
            config?.stage2DurationMinutes ?: DEFAULT_STAGE2_MINUTES
        )

        // The nuclear alarm vibrates with the same setting as Stage 1.
        val nuclear = AlarmSounds.nuclearByKey(config?.nuclearSoundId).displayName
        binding.tileNuclear.tvValue.text =
            if (config?.vibrationEnabled != false) getString(R.string.alarm_tab_nuclear_vibrate, nuclear)
            else nuclear
    }

    private fun renderContent(toggles: List<ContentToggle>) {
        val byType = toggles.associateBy { it.contentType }
        var on = 0
        contentItems.forEach { (type, item) ->
            val toggle = byType[type]
            val enabled = toggle?.isEnabled == true
            if (enabled) on++

            item.ivMark.setImageResource(if (enabled) R.drawable.ic_content_on else R.drawable.ic_content_off)
            item.tvLabel.setTextColor(color(if (enabled) MaterialR.attr.colorOnSurface else MaterialR.attr.colorOnSurfaceVariant))
            item.tvLabel.text = SpannableStringBuilder(getString(contentLabel(type))).apply {
                if (type == ContentType.STRETCH && toggle != null) {
                    inSpans(ForegroundColorSpan(color(MaterialR.attr.colorOnSurfaceVariant))) {
                        append(getString(R.string.alarm_tab_content_stretch_minutes, toggle.durationMinutes))
                    }
                }
            }
            item.root.isSelected = enabled
            item.root.isEnabled = toggle != null
            item.root.setOnClickListener { viewModel.setContentEnabled(type, !enabled) }
        }
        binding.tvContentCount.text = getString(R.string.alarm_tab_content_count, on, contentItems.size)
    }

    @StringRes
    private fun contentLabel(type: ContentType): Int = when (type) {
        ContentType.QUOTE -> R.string.alarm_tab_content_quote
        ContentType.STRETCH -> R.string.alarm_tab_content_stretch
        ContentType.DAILY_SCHEDULE -> R.string.alarm_tab_content_schedule
        ContentType.CHALKBOARD -> R.string.alarm_tab_content_todo
    }

    private fun setUpTile(
        tile: ItemAlarmTileBinding,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        section: String
    ) {
        tile.ivBadge.setImageResource(icon)
        tile.tvTitle.setText(title)
        tile.root.setOnClickListener { openSettings(section) }
    }

    private fun openSettings(section: String) {
        findNavController().navigate(
            R.id.action_alarm_to_settings,
            bundleOf(SettingsFragment.ARG_SECTION to section)
        )
    }

    private fun color(attr: Int): Int = MaterialColors.getColor(binding.root, attr)

    // --- Actions ---

    private fun showTimePicker() {
        val minutes = currentMinutes()
        TimePickerDialog(
            requireContext(),
            { _, hour, minute ->
                val newMinutes = hour * 60 + minute
                viewModel.setAlarmTime(newMinutes)
                // Keep a live alarm in sync with the new time.
                if (currentEnabled()) scheduleOrPrompt(newMinutes)
            },
            minutes / 60,
            minutes % 60,
            DateFormat.is24HourFormat(requireContext())
        ).show()
    }

    /**
     * The switch flips visually on tap. When a PIN guards it, revert that flip
     * until the PIN is confirmed, then re-apply on success; a wrong/cancelled PIN
     * leaves the switch in its original state.
     */
    private fun onToggleEnabledTapped() {
        val desired = binding.switchEnabled.isChecked
        if (pinManager.isPinSet()) binding.switchEnabled.isChecked = !desired
        PinPrompts.guard(requireContext(), pinManager) {
            binding.switchEnabled.isChecked = desired
            onToggleEnabled(desired)
        }
    }

    private fun onToggleEnabled(enabled: Boolean) {
        if (enabled) {
            if (!scheduler.canScheduleExactAlarms()) {
                binding.switchEnabled.isChecked = false // can't honour it yet
                promptForExactAlarmPermission()
                return
            }
            viewModel.setEnabled(true)
            scheduler.scheduleDailyAlarm(currentMinutes())
        } else {
            viewModel.setEnabled(false)
            scheduler.cancelAlarm()
        }
    }

    private fun scheduleOrPrompt(minutes: Int) {
        if (scheduler.canScheduleExactAlarms()) {
            scheduler.scheduleDailyAlarm(minutes)
        } else {
            promptForExactAlarmPermission()
        }
    }

    private fun promptForExactAlarmPermission() {
        Toast.makeText(requireContext(), R.string.home_exact_alarm_needed, Toast.LENGTH_LONG).show()
        scheduler.exactAlarmSettingsIntent()?.let { startActivity(it) }
    }

    private fun currentMinutes(): Int =
        viewModel.config.value?.alarmTime ?: HomeViewModel.DEFAULT_ALARM_MINUTES

    private fun currentEnabled(): Boolean = viewModel.config.value?.isEnabled == true

    private fun formatTime(minutes: Int): String {
        val pattern = if (DateFormat.is24HourFormat(requireContext())) "HH:mm" else "h:mm a"
        return LocalTime.of(minutes / 60, minutes % 60)
            .format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    // --- Temporary dev tools ---

    private fun setupDevTools() {
        binding.btnTestAlarm.setOnClickListener {
            if (!scheduler.canScheduleExactAlarms()) {
                promptForExactAlarmPermission()
                return@setOnClickListener
            }
            scheduler.scheduleAlarm(System.currentTimeMillis() + 60_000L)
            Toast.makeText(requireContext(), "Test alarm in 1 minute", Toast.LENGTH_SHORT).show()
        }
        binding.btnTestCountdown.setOnClickListener {
            CountdownService.start(requireContext(), 15)
            Toast.makeText(requireContext(), "15s countdown started", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** Mirror AlarmConfig's defaults, for before any config row exists. */
        const val DEFAULT_SEQUENCE_LENGTH = 5
        const val DEFAULT_STAGE2_MINUTES = 10
    }
}
