package com.personalmorningalarm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.entity.AlarmConfig
import com.personalmorningalarm.data.entity.ContentToggle
import com.personalmorningalarm.data.model.ContentType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: AlarmRepository) : ViewModel() {

    /** Content-screen toggles for the settings switches. */
    val contentToggles: StateFlow<List<ContentToggle>> = repository.observeContentToggles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How many NFC checkpoints Stage 2 requires (the saved config value). */
    val sequenceLength: StateFlow<Int> = repository.observeCurrentConfig()
        .map { it?.sequenceLength ?: DEFAULT_SEQUENCE_LENGTH }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_SEQUENCE_LENGTH)

    /** Number of registered, active tags — caps the picker's range. */
    val activeTagCount: StateFlow<Int> = repository.observeActiveTagCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Stage 2 countdown length in minutes (the saved config value). */
    val stage2Duration: StateFlow<Int> = repository.observeCurrentConfig()
        .map { it?.stage2DurationMinutes ?: DEFAULT_STAGE2_DURATION }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_STAGE2_DURATION)

    /** Full current config, for the sound/volume/vibration controls. */
    val config: StateFlow<AlarmConfig?> = repository.observeCurrentConfig()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setSequenceLength(length: Int) = persist { it.copy(sequenceLength = length) }

    fun setStage2Duration(minutes: Int) = persist { it.copy(stage2DurationMinutes = minutes) }

    fun setStage1Sound(key: String) = persist { it.copy(stage1SoundId = key) }

    fun setNuclearSound(key: String) = persist { it.copy(nuclearSoundId = key) }

    fun setStage1Volume(volume: Int) = persist { it.copy(stage1Volume = volume) }

    fun setVibrationEnabled(enabled: Boolean) = persist { it.copy(vibrationEnabled = enabled) }

    /**
     * Applies [transform] to the single config row (updating in place, or creating a
     * disabled default if none exists yet) so config rows don't accumulate.
     */
    private fun persist(transform: (AlarmConfig) -> AlarmConfig) {
        viewModelScope.launch {
            val current = repository.getCurrentConfig()
            if (current == null) {
                repository.saveConfig(
                    transform(
                        AlarmConfig(alarmTime = HomeViewModel.DEFAULT_ALARM_MINUTES, isEnabled = false)
                    )
                )
            } else {
                repository.updateConfig(transform(current))
            }
        }
    }

    fun setContentEnabled(type: ContentType, enabled: Boolean) {
        viewModelScope.launch {
            repository.getContentToggle(type)?.let {
                repository.updateContentToggle(it.copy(isEnabled = enabled))
            }
        }
    }

    companion object {
        private const val DEFAULT_SEQUENCE_LENGTH = 5
        private const val DEFAULT_STAGE2_DURATION = 10
    }
}
