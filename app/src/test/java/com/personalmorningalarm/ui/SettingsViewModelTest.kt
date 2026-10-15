package com.personalmorningalarm.ui

import com.personalmorningalarm.data.entity.AlarmConfig
import com.personalmorningalarm.data.entity.ContentToggle
import com.personalmorningalarm.data.entity.NfcTag
import com.personalmorningalarm.data.model.ContentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * State-management / emission tests for [SettingsViewModel]: content-toggle,
 * sequence-length, stage-2 duration and active-tag-count flows track the DB, and
 * the setters persist.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest : ViewModelTestSupport() {

    @Test
    fun `content toggles flow reflects the database and setContentEnabled persists`() {
        runBlocking {
            repo.saveContentToggles(
                listOf(
                    ContentToggle(contentType = ContentType.QUOTE, isEnabled = true, displayOrder = 0),
                    ContentToggle(contentType = ContentType.STRETCH, isEnabled = true, displayOrder = 1)
                )
            )
        }
        val vm = track(SettingsViewModel(repo))
        keepSubscribed(vm.contentToggles)

        settledValue(vm.contentToggles) { it.size == 2 }

        vm.setContentEnabled(ContentType.STRETCH, false)
        val toggles = settledValue(vm.contentToggles) { list ->
            list.firstOrNull { it.contentType == ContentType.STRETCH }?.isEnabled == false
        }
        assertTrue(toggles.first { it.contentType == ContentType.QUOTE }.isEnabled)
    }

    @Test
    fun `setStretchDuration persists on the stretch toggle`() {
        runBlocking {
            repo.saveContentToggle(ContentToggle(contentType = ContentType.STRETCH, durationMinutes = 5))
        }
        val vm = track(SettingsViewModel(repo))
        keepSubscribed(vm.contentToggles)

        vm.setStretchDuration(10)
        settledValue(vm.contentToggles) { list ->
            list.firstOrNull { it.contentType == ContentType.STRETCH }?.durationMinutes == 10
        }
    }

    @Test
    fun `sequence length and stage2 duration flows fall back to defaults then track config`() {
        val vm = track(SettingsViewModel(repo))
        keepSubscribed(vm.sequenceLength, vm.stage2Duration)

        // No config yet -> defaults.
        assertEquals(5, settledValue(vm.sequenceLength) { it == 5 })
        assertEquals(10, settledValue(vm.stage2Duration) { it == 10 })

        vm.setSequenceLength(3)
        assertEquals(3, settledValue(vm.sequenceLength) { it == 3 })

        vm.setStage2Duration(12)
        assertEquals(12, settledValue(vm.stage2Duration) { it == 12 })
    }

    @Test
    fun `active tag count flow tracks registered active tags`() {
        val vm = track(SettingsViewModel(repo))
        keepSubscribed(vm.activeTagCount)

        assertEquals(0, settledValue(vm.activeTagCount) { it == 0 })

        runBlocking {
            repo.registerTag(NfcTag(tagId = "AA11", label = "Kitchen", location = "Fridge"))
            repo.registerTag(NfcTag(tagId = "BB22", label = "Bath", location = "Mirror", isActive = false))
        }
        // Only the active tag counts.
        assertEquals(1, settledValue(vm.activeTagCount) { it == 1 })
    }

    @Test
    fun `sound and volume setters persist to the config row`() {
        runBlocking { repo.saveConfig(AlarmConfig(alarmTime = 390)) }
        val vm = track(SettingsViewModel(repo))
        keepSubscribed(vm.config)

        // Each setter is a read-modify-write on the single row, so await each
        // change landing before the next (mirrors the one-tap-at-a-time UI).
        vm.setStage1Volume(40)
        settledValue(vm.config) { it?.stage1Volume == 40 }
        vm.setVibrationEnabled(false)
        settledValue(vm.config) { it?.vibrationEnabled == false }
        vm.setStage1Sound("gentle_warm_waves")
        val config = settledValue(vm.config) { it?.stage1SoundId == "gentle_warm_waves" }

        assertEquals(40, config!!.stage1Volume)
        assertFalse(config.vibrationEnabled)
        assertEquals(1, runBlocking { repo.getAllConfigs().size })
    }
}
