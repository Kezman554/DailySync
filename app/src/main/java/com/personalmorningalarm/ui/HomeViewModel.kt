package com.personalmorningalarm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.entity.AlarmConfig
import com.personalmorningalarm.data.entity.ContentToggle
import com.personalmorningalarm.data.model.ContentType
import com.personalmorningalarm.data.model.MorningGoal
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Streak + weekly success snapshot for the Alarm tab (the tab shows the streak). */
data class HomeStats(
    val currentStreak: Int = 0,
    val successDays: Int = 0,
    val attemptedDays: Int = 0
)

/**
 * Home-screen state: the current [AlarmConfig] and derived [HomeStats], both as
 * reactive flows off Room so the UI stays in sync with the database. Persists
 * config edits; the actual alarm (de)scheduling lives in the fragment, which has
 * the Context and permission flow.
 */
class HomeViewModel(private val repository: AlarmRepository) : ViewModel() {

    val config: StateFlow<AlarmConfig?> = repository.observeCurrentConfig()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val stats: StateFlow<HomeStats> = repository.observeEvents()
        .map {
            val (success, attempted) = repository.getWeeklySuccessCounts()
            HomeStats(
                currentStreak = repository.getCurrentStreak(),
                successDays = success,
                attemptedDays = attempted
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeStats())

    /** The morning content screens, for the Alarm tab's tick grid. */
    val contentToggles: StateFlow<List<ContentToggle>> = repository.observeContentToggles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setAlarmTime(minutesSinceMidnight: Int) =
        persist { it.copy(alarmTime = minutesSinceMidnight) }

    fun setEnabled(enabled: Boolean) = persist { it.copy(isEnabled = enabled) }

    fun setMorningGoal(goal: MorningGoal) = persist { it.copy(morningGoal = goal) }

    /** Turns one morning content screen on or off (the same setting Settings edits). */
    fun setContentEnabled(type: ContentType, enabled: Boolean) {
        viewModelScope.launch {
            repository.getContentToggle(type)?.let {
                repository.updateContentToggle(it.copy(isEnabled = enabled))
            }
        }
    }

    /**
     * Applies [transform] to the single config row, updating it in place (or
     * creating a default one if none exists yet) so we never accumulate rows.
     */
    private fun persist(transform: (AlarmConfig) -> AlarmConfig) {
        viewModelScope.launch {
            val current = repository.getCurrentConfig()
            if (current == null) {
                // Start disabled so e.g. setting a time before enabling doesn't
                // claim the alarm is on without anything being scheduled.
                repository.saveConfig(
                    transform(AlarmConfig(alarmTime = DEFAULT_ALARM_MINUTES, isEnabled = false))
                )
            } else {
                repository.updateConfig(transform(current))
            }
        }
    }

    companion object {
        /** 06:30 — used as the starting point before the user has set a time. */
        const val DEFAULT_ALARM_MINUTES = 6 * 60 + 30
    }
}
