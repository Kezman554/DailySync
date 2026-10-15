package com.personalmorningalarm.ui

import com.personalmorningalarm.data.entity.AlarmConfig
import com.personalmorningalarm.data.entity.AlarmEvent
import com.personalmorningalarm.data.entity.ContentToggle
import com.personalmorningalarm.data.model.ContentType
import com.personalmorningalarm.data.model.MorningGoal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * State-management / emission tests for [HomeViewModel]: its config and stats
 * StateFlows track the database, and its edit methods persist to the single
 * config row.
 */
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest : ViewModelTestSupport() {

    @Test
    fun `config flow emits the saved config`() {
        runBlocking { repo.saveConfig(AlarmConfig(alarmTime = 390, isEnabled = true)) }
        val vm = track(HomeViewModel(repo))
        keepSubscribed(vm.config)

        val config = settledValue(vm.config) { it?.alarmTime == 390 }
        assertTrue(config!!.isEnabled)
    }

    @Test
    fun `setAlarmTime on an empty database creates a disabled default config`() {
        val vm = track(HomeViewModel(repo))
        keepSubscribed(vm.config)

        vm.setAlarmTime(7 * 60) // 07:00
        val config = settledValue(vm.config) { it?.alarmTime == 7 * 60 }
        // Starts disabled so setting a time doesn't imply the alarm is armed.
        assertFalse(config!!.isEnabled)
    }

    @Test
    fun `edits update the single config row in place`() {
        val vm = track(HomeViewModel(repo))
        keepSubscribed(vm.config)

        vm.setAlarmTime(400)
        settledValue(vm.config) { it?.alarmTime == 400 }
        vm.setEnabled(true)
        settledValue(vm.config) { it?.isEnabled == true }
        vm.setMorningGoal(MorningGoal.PROJECT)
        val config = settledValue(vm.config) { it?.morningGoal == MorningGoal.PROJECT }

        assertEquals(400, config!!.alarmTime)
        assertTrue(config.isEnabled)
        // All edits landed on ONE row, not accumulated rows.
        assertEquals(1, runBlocking { repo.getAllConfigs().size })
    }

    @Test
    fun `stats flow reflects a current streak and weekly counts`() {
        val today = LocalDate.now()
        runBlocking {
            repo.recordEvent(AlarmEvent(date = today.toString(), stage1Success = true, stage2Success = true))
            repo.recordEvent(AlarmEvent(date = today.minusDays(1).toString(), stage1Success = true, stage2Success = true))
        }
        val vm = track(HomeViewModel(repo))
        keepSubscribed(vm.stats)

        val stats = settledValue(vm.stats) { it.currentStreak == 2 }
        assertEquals(2, stats.successDays)
        assertEquals(2, stats.attemptedDays)
    }

    @Test
    fun `setContentEnabled flips just that content screen`() {
        runBlocking {
            repo.saveContentToggles(ContentType.entries.map { ContentToggle(contentType = it, isEnabled = true) })
        }
        val vm = track(HomeViewModel(repo))
        keepSubscribed(vm.contentToggles)
        settledValue(vm.contentToggles) { it.size == ContentType.entries.size }

        vm.setContentEnabled(ContentType.STRETCH, false)
        val toggles = settledValue(vm.contentToggles) { list ->
            list.any { it.contentType == ContentType.STRETCH && !it.isEnabled }
        }
        assertEquals(
            ContentType.entries.filter { it != ContentType.STRETCH }.toSet(),
            toggles.filter { it.isEnabled }.map { it.contentType }.toSet()
        )
    }
}
