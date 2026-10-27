package com.personalmorningalarm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.personalmorningalarm.R
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.model.ContentType
import com.personalmorningalarm.databinding.FragmentMorningContentBinding
import kotlinx.coroutines.launch

/**
 * Morning content, from the Alarm tab: which screens show between NFC taps, how
 * long the stretch runs, and the ways into the quotes and stretch routines.
 */
class MorningContentFragment : Fragment() {

    private var _binding: FragmentMorningContentBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels {
        ViewModelFactory(AlarmRepository(AppDatabase.getInstance(requireContext())))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMorningContentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindScreenTitle(binding.titleBar, R.string.alarm_tab_content)

        // onClick fires only on user taps (not programmatic setChecked), so no feedback loop.
        binding.switchQuote.setOnClickListener {
            viewModel.setContentEnabled(ContentType.QUOTE, binding.switchQuote.isChecked)
        }
        binding.switchStretch.setOnClickListener {
            viewModel.setContentEnabled(ContentType.STRETCH, binding.switchStretch.isChecked)
        }
        binding.switchDailySchedule.setOnClickListener {
            viewModel.setContentEnabled(ContentType.DAILY_SCHEDULE, binding.switchDailySchedule.isChecked)
        }
        binding.switchChalkboard.setOnClickListener {
            viewModel.setContentEnabled(ContentType.CHALKBOARD, binding.switchChalkboard.isChecked)
        }

        binding.rowManageQuotes.setOnClickListener {
            findNavController().navigate(R.id.action_morningContent_to_quotes)
        }
        binding.rowManageRoutines.setOnClickListener {
            findNavController().navigate(R.id.action_morningContent_to_routines)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.contentToggles.collect { toggles ->
                    toggles.forEach { t ->
                        when (t.contentType) {
                            ContentType.QUOTE -> binding.switchQuote.isChecked = t.isEnabled
                            ContentType.STRETCH -> binding.switchStretch.isChecked = t.isEnabled
                            ContentType.DAILY_SCHEDULE ->
                                binding.switchDailySchedule.isChecked = t.isEnabled
                            ContentType.CHALKBOARD ->
                                binding.switchChalkboard.isChecked = t.isEnabled
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
