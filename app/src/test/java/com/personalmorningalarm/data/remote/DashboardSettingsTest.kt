package com.personalmorningalarm.data.remote

import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.remote.DashboardSettings.Companion.DEFAULT_HOST
import com.personalmorningalarm.data.remote.DashboardSettings.Companion.normalise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The dashboard's address follows Alfred's rules exactly — only the port differs. */
@RunWith(RobolectricTestRunner::class)
class DashboardSettingsTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val settings = DashboardSettings(context)

    @Test
    fun `defaults to the dashboard on port 8500 until configured`() {
        assertEquals("192.168.1.100:8500", settings.getHost())
        assertEquals("http://192.168.1.100:8500/", settings.baseUrl())
    }

    @Test
    fun `is its own setting, independent of Alfred's`() {
        AlfredSettings(context).setHost("10.0.0.5:8200")
        assertEquals(DEFAULT_HOST, settings.getHost())

        settings.setHost("10.0.0.6:9500")
        assertEquals("10.0.0.6:9500", settings.getHost())
        assertEquals("10.0.0.5:8200", AlfredSettings(context).getHost())
    }

    @Test
    fun `a bare host gets the dashboard's port, not Alfred's`() {
        assertEquals("alfred.local:8500", normalise("alfred.local"))
    }

    @Test
    fun `strips a pasted scheme, path and surrounding space`() {
        assertEquals("10.0.0.5:8500", normalise("  http://10.0.0.5:8500/api/presence  "))
        assertEquals("10.0.0.5:8500", normalise("https://10.0.0.5:8500"))
    }

    @Test
    fun `blank input falls back to the default`() {
        assertEquals(DEFAULT_HOST, normalise("   "))

        settings.setHost("")
        assertEquals(DEFAULT_HOST, settings.getHost())
    }

    @Test
    fun `validation matches Alfred's`() {
        assertTrue(DashboardSettings.isValid("192.168.1.100:8500"))
        assertTrue(DashboardSettings.isValid("alfred.local"))
        assertTrue(DashboardSettings.isValid(""))
        assertFalse(DashboardSettings.isValid("192.168.1.100:"))
        assertFalse(DashboardSettings.isValid("192.168.1.100:99999"))
        assertFalse(DashboardSettings.isValid("not a host"))
    }
}
