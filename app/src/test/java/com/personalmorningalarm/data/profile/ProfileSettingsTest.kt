package com.personalmorningalarm.data.profile

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.phone.ListHome
import com.personalmorningalarm.data.phone.ListSettings
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResponseCache
import com.personalmorningalarm.util.AlarmScheduler
import com.personalmorningalarm.util.ThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/** Saving the profile, telling old installs from new, and what choosing one does. */
@RunWith(RobolectricTestRunner::class)
class ProfileSettingsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Before
    fun setUp() {
        listOf(ThemeManager.PREFS_NAME, "alfred_cache").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test
    fun `an install from before profiles carries on as Nick, without asking`() {
        val settings = ProfileSettings(context)
        settings.settle(existingInstall = true)

        assertEquals(Profile.NICK, settings.profile())
    }

    @Test
    fun `a fresh install waits for the first-run choice`() {
        val settings = ProfileSettings(context)
        settings.settle(existingInstall = false)

        assertNull(settings.profile())
    }

    @Test
    fun `a fresh install closed before choosing still asks, though its database now exists`() {
        val settings = ProfileSettings(context)
        settings.settle(existingInstall = false)
        settings.settle(existingInstall = true)

        assertNull(settings.profile())
    }

    @Test
    fun `a chosen profile is kept, whatever the next start finds`() {
        val settings = ProfileSettings(context)
        settings.settle(existingInstall = false)
        settings.setProfile(Profile.JESS)
        settings.settle(existingInstall = true)

        assertEquals(Profile.JESS, ProfileSettings(context).profile())
    }

    @Test
    fun `setting up as Jess puts her to-do on the phone`() {
        ProfileSettings(context).settle(existingInstall = false)
        ProfileSetup.choose(context, Profile.JESS)

        assertEquals(Profile.JESS, ProfileSettings(context).profile())
        assertEquals(ListHome.PHONE, ListSettings(context).todoHome())
    }

    @Test
    fun `setting up as Nick leaves the to-do to its usual default`() {
        ListSettings(context).setTodoHome(ListHome.HUB)
        ProfileSettings(context).settle(existingInstall = false)
        ProfileSetup.choose(context, Profile.NICK)

        assertEquals(ListHome.HUB, ListSettings(context).todoHome())
    }

    @Test
    fun `switching in Settings leaves the to-do where it is, and drops the other person's saved lists`() {
        ProfileSettings(context).setProfile(Profile.NICK)
        ListSettings(context).setTodoHome(ListHome.HUB)
        val cache = AlfredResponseCache(context)
        cache.put(AlfredRepository.ENDPOINT_SHOPPING, "[]")

        ProfileSetup.choose(context, Profile.JESS)

        assertEquals(ListHome.HUB, ListSettings(context).todoHome())
        assertNull(cache.get(AlfredRepository.ENDPOINT_SHOPPING))
    }

    @Test
    fun `Jess's profile never schedules an alarm, whoever asks`() {
        ProfileSettings(context).setProfile(Profile.JESS)

        assertFalse(AlarmScheduler(context).scheduleDailyAlarm(6 * 60 + 30))
        assertNull(alarms.nextScheduledAlarm)
    }

    @Test
    fun `before the first-run choice nothing is scheduled either`() {
        ProfileSettings(context).settle(existingInstall = false)

        assertFalse(AlarmScheduler(context).scheduleDailyAlarm(6 * 60 + 30))
        assertNull(alarms.nextScheduledAlarm)
    }

    @Test
    fun `Nick's profile schedules as it always did, and switching to Jess cancels it`() {
        ProfileSettings(context).setProfile(Profile.NICK)

        assertTrue(AlarmScheduler(context).scheduleDailyAlarm(6 * 60 + 30))
        assertNotNull(alarms.nextScheduledAlarm)

        ProfileSetup.choose(context, Profile.JESS)

        assertNull(alarms.nextScheduledAlarm)
        assertFalse(AlarmScheduler(context).isAlarmScheduled())
    }
}
