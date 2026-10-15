package com.personalmorningalarm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.personalmorningalarm.R
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.databinding.DialogDurationSliderBinding
import com.personalmorningalarm.databinding.FragmentNfcSettingsBinding
import com.personalmorningalarm.util.PinManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * NFC tags, from the Alarm tab: how many checkpoints Stage 2 asks for, its time
 * limit (PIN-protected), and the way into the registered tags.
 */
class NfcSettingsFragment : Fragment() {

    private var _binding: FragmentNfcSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels {
        ViewModelFactory(AlarmRepository(AppDatabase.getInstance(requireContext())))
    }

    private lateinit var pinManager: PinManager

    /** What the stepper shows, and its ceiling; the buttons step from these. */
    private var taps = 1
    private var maxTaps = 1

    /** Cached so the time-limit dialog opens on the saved value. */
    private var stage2Duration = DEFAULT_STAGE2_DURATION

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNfcSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pinManager = PinManager(requireContext())
        bindScreenTitle(binding.titleBar, R.string.alarm_tab_nfc)

        binding.btnTapsMinus.setOnClickListener { viewModel.setSequenceLength((taps - 1).coerceAtLeast(1)) }
        binding.btnTapsPlus.setOnClickListener { viewModel.setSequenceLength((taps + 1).coerceAtMost(maxTaps)) }

        binding.rowTimeLimit.setOnClickListener {
            PinPrompts.guard(requireContext(), pinManager) { showStage2DurationDialog() }
        }
        binding.rowManageTags.setOnClickListener {
            findNavController().navigate(R.id.action_nfcSettings_to_nfcTags)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.sequenceLength, viewModel.activeTagCount) { length, tags -> length to tags }
                        .collect { (length, tags) -> renderTaps(length, tags) }
                }
                launch {
                    viewModel.stage2Duration.collect { minutes ->
                        stage2Duration = minutes
                        binding.tvTimeLimit.text = getString(R.string.stage2_duration_value, minutes)
                    }
                }
            }
        }
    }

    /**
     * Reflects the saved checkpoint count against the registered-tag pool. The
     * ceiling is fixed (every registered tag, plus headroom for repeats) so it
     * never collapses when the count is dialled below the tag count. Counts above
     * the tag count are allowed — the hint says those taps repeat.
     */
    private fun renderTaps(length: Int, tagCount: Int) {
        binding.tvTagCount.text = resources.getQuantityString(R.plurals.row_tag_count, tagCount, tagCount)
        if (tagCount == 0) {
            taps = 1
            maxTaps = 1
            binding.tvTaps.text = "1"
            binding.btnTapsMinus.isEnabled = false
            binding.btnTapsPlus.isEnabled = false
            binding.tvTapsHint.text = getString(R.string.nfc_taps_no_tags)
            return
        }
        maxTaps = maxOf(tagCount, MAX_SEQUENCE_LENGTH)
        taps = length.coerceIn(1, maxTaps)
        binding.tvTaps.text = taps.toString()
        binding.btnTapsMinus.isEnabled = taps > 1
        binding.btnTapsPlus.isEnabled = taps < maxTaps
        binding.tvTapsHint.text = if (taps > tagCount) {
            getString(R.string.nfc_taps_hint_repeats, taps, tagCount)
        } else {
            getString(R.string.nfc_taps_hint, taps, tagCount)
        }
    }

    /** Slider dialog (5–15 min) for the Stage 2 time limit. */
    private fun showStage2DurationDialog() {
        val dialogBinding = DialogDurationSliderBinding.inflate(layoutInflater)
        val start = stage2Duration.coerceIn(MIN_STAGE2_DURATION, MAX_STAGE2_DURATION)
        dialogBinding.sliderDuration.value = start.toFloat()
        dialogBinding.tvDurationValue.text = getString(R.string.stage2_duration_value, start)
        dialogBinding.sliderDuration.addOnChangeListener { _, value, _ ->
            dialogBinding.tvDurationValue.text =
                getString(R.string.stage2_duration_value, value.toInt())
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.stage2_duration_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                viewModel.setStage2Duration(dialogBinding.sliderDuration.value.toInt())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** Stepper ceiling (also allows more if more tags are registered). */
        const val MAX_SEQUENCE_LENGTH = 10

        /** Stage 2 time-limit bounds, per the PRD (5–15 min). */
        const val MIN_STAGE2_DURATION = 5
        const val MAX_STAGE2_DURATION = 15
        const val DEFAULT_STAGE2_DURATION = 10
    }
}
