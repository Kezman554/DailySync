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
import com.google.android.material.slider.Slider
import com.personalmorningalarm.R
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.entity.AlarmConfig
import com.personalmorningalarm.data.model.AlarmSounds
import com.personalmorningalarm.databinding.FragmentWakeSoundBinding
import com.personalmorningalarm.util.SoundPreviewPlayer
import kotlinx.coroutines.launch

/** Wake-up sound, from the Alarm tab: the Stage 1 sound (with preview) and its volume. */
class WakeSoundFragment : Fragment() {

    private var _binding: FragmentWakeSoundBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels {
        ViewModelFactory(AlarmRepository(AppDatabase.getInstance(requireContext())))
    }

    private val preview = SoundPreviewPlayer()

    /** The saved choice, so Preview plays it and the picker opens on it. */
    private var soundKey: String = AlarmSounds.defaultStage1.key

    /** True while the slider is held, so a save landing mid-drag doesn't yank it. */
    private var dragging = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWakeSoundBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindScreenTitle(binding.titleBar, R.string.alarm_tab_sound)

        binding.rowSound.setOnClickListener {
            showSoundPicker(
                requireContext(), preview, R.string.sound_pick_stage1_title,
                AlarmSounds.stage1, soundKey, volume()
            ) { viewModel.setStage1Sound(it) }
        }
        binding.btnPreview.contentDescription = getString(R.string.preview_sound, getString(R.string.row_sound))
        binding.btnPreview.setOnClickListener {
            preview.play(requireContext(), AlarmSounds.stage1ByKey(soundKey), volume())
        }

        // Live readout while dragging; persist once on release, not every tick.
        binding.sliderVolume.addOnChangeListener { _, value, _ -> renderVolume(value.toInt()) }
        binding.sliderVolume.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                dragging = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                dragging = false
                viewModel.setStage1Volume(slider.value.toInt())
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.config.collect(::render)
            }
        }
    }

    private fun render(config: AlarmConfig?) {
        val sound = AlarmSounds.stage1ByKey(config?.stage1SoundId)
        soundKey = sound.key
        binding.tvSound.text = sound.displayName

        val volume = (config?.stage1Volume ?: 100).coerceIn(0, 100)
        if (!dragging && binding.sliderVolume.value != volume.toFloat()) {
            binding.sliderVolume.value = volume.toFloat()
        }
        renderVolume(binding.sliderVolume.value.toInt())
    }

    private fun renderVolume(percent: Int) {
        binding.tvVolume.text = getString(R.string.row_volume_value, percent)
    }

    /** The slider's volume as a 0f-1f fraction, for previews. */
    private fun volume(): Float = (binding.sliderVolume.value / 100f).coerceIn(0f, 1f)

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
