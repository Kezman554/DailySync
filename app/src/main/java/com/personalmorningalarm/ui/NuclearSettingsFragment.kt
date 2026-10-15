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
import com.personalmorningalarm.R
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.entity.AlarmConfig
import com.personalmorningalarm.data.model.AlarmSounds
import com.personalmorningalarm.databinding.FragmentNuclearSettingsBinding
import com.personalmorningalarm.util.SoundPreviewPlayer
import kotlinx.coroutines.launch

/** Nuclear, from the Alarm tab: the sound when Stage 2 runs out, and whether it vibrates. */
class NuclearSettingsFragment : Fragment() {

    private var _binding: FragmentNuclearSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels {
        ViewModelFactory(AlarmRepository(AppDatabase.getInstance(requireContext())))
    }

    private val preview = SoundPreviewPlayer()

    /** The saved choice, so Preview plays it and the picker opens on it. */
    private var soundKey: String = AlarmSounds.defaultNuclear.key

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNuclearSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindScreenTitle(binding.titleBar, R.string.alarm_tab_nuclear)

        // The nuclear alarm always plays at full volume, so preview it at full too.
        binding.rowNuclearSound.setOnClickListener {
            showSoundPicker(
                requireContext(), preview, R.string.sound_pick_nuclear_title,
                AlarmSounds.nuclear, soundKey, 1f
            ) { viewModel.setNuclearSound(it) }
        }
        binding.btnPreview.contentDescription =
            getString(R.string.preview_sound, getString(R.string.row_nuclear_sound))
        binding.btnPreview.setOnClickListener {
            preview.play(requireContext(), AlarmSounds.nuclearByKey(soundKey), 1f)
        }

        binding.switchVibration.setOnClickListener {
            viewModel.setVibrationEnabled(binding.switchVibration.isChecked)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.config.collect(::render)
            }
        }
    }

    private fun render(config: AlarmConfig?) {
        val sound = AlarmSounds.nuclearByKey(config?.nuclearSoundId)
        soundKey = sound.key
        binding.tvNuclearSound.text = sound.displayName
        binding.switchVibration.isChecked = config?.vibrationEnabled ?: true
    }

    override fun onPause() {
        super.onPause()
        preview.stop() // don't let a preview keep playing after leaving
    }

    override fun onDestroyView() {
        super.onDestroyView()
        preview.release()
        _binding = null
    }
}
