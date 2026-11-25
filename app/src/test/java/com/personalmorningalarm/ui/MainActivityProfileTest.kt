package com.personalmorningalarm.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.R
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.profile.ProfileSettings
import com.personalmorningalarm.util.ThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/**
 * The first launch as each profile: what it asks for and what the header offers.
 * Notifications start switched off, so a profile that wants them asks.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityProfileTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        context.getSharedPreferences(ThemeManager.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .setNotificationsEnabled(false)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun launch(profile: Profile?): MainActivity {
        val settings = ProfileSettings(context)
        if (profile == null) settings.settle(existingInstall = false) else settings.setProfile(profile)
        return Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    private fun MainActivity.requested(): List<String> =
        shadowOf(this).lastRequestedPermission?.requestedPermissions?.toList().orEmpty()

    @Test
    fun `Jess's first launch asks for nothing and shows no alarm prompt`() {
        val activity = launch(Profile.JESS)

        assertTrue(activity.requested().isEmpty())
        assertNull(ShadowDialog.getLatestDialog())
    }

    @Test
    fun `Jess's header has no Home or Alarm pills and no alarm line`() {
        val activity = launch(Profile.JESS)

        assertEquals(View.GONE, activity.findViewById<View>(R.id.pills).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.tv_header_alarm).visibility)
    }

    @Test
    fun `Nick's first launch still asks for notifications and battery, and keeps the pills`() {
        val activity = launch(Profile.NICK)

        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), activity.requested())
        assertNotNull(ShadowDialog.getLatestDialog())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pills).visibility)
    }

    @Test
    fun `a fresh install asks whose phone it is before anything else`() {
        val activity = launch(null)

        assertTrue(activity.requested().isEmpty())
        val chooser = activity.supportFragmentManager.findFragmentByTag(ProfileChooserDialog.TAG)
        assertNotNull(chooser)
        assertFalse((chooser as ProfileChooserDialog).isCancelable)
    }

    @Test
    fun `choosing a profile takes the chooser away, so the recreated screen doesn't bring it back`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        ProfileSettings(context).settle(existingInstall = false)
        val activity = controller.setup().get()

        activity.onProfileChosen(Profile.JESS)

        assertNull(activity.supportFragmentManager.findFragmentByTag(ProfileChooserDialog.TAG))
        assertEquals(Profile.JESS, ProfileSettings(context).profile())
    }
}
