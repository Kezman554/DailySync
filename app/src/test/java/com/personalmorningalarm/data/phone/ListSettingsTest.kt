package com.personalmorningalarm.data.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.remote.AlfredResponseCache
import com.personalmorningalarm.data.remote.AlfredSettings
import com.personalmorningalarm.util.ThemeManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Where new lists start, and where the to-do lives until it's chosen. */
@RunWith(RobolectricTestRunner::class)
class ListSettingsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        listOf(ThemeManager.PREFS_NAME, "alfred_cache").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun `with no hub address saved and no answer from Alfred, new lists and the to-do start on the phone`() {
        val settings = ListSettings(context)

        assertEquals(ListHome.PHONE, settings.defaultHome())
        assertEquals(ListHome.PHONE, settings.todoHome())
    }

    @Test
    fun `with a hub address saved, new lists start on the hub, and the to-do stays there`() {
        AlfredSettings(context).setHost("192.168.1.100:8200")
        val settings = ListSettings(context)

        assertEquals(ListHome.HUB, settings.defaultHome())
        assertEquals(ListHome.HUB, settings.todoHome())
    }

    @Test
    fun `a hub at the default address that has answered counts, though no address was ever saved`() {
        // The A32's case: Alfred at the built-in address, and a saved answer from him.
        AlfredResponseCache(context).put("chalkboard", "[]")
        val settings = ListSettings(context)

        assertEquals(ListHome.HUB, settings.defaultHome())
        assertEquals(ListHome.HUB, settings.todoHome())
    }

    @Test
    fun `the to-do keeps its first home, so it never moves by itself`() {
        AlfredResponseCache(context).put("chalkboard", "[]")
        assertEquals(ListHome.HUB, ListSettings(context).todoHome())

        AlfredResponseCache(context).clear("chalkboard")

        assertEquals(ListHome.PHONE, ListSettings(context).defaultHome())
        assertEquals(ListHome.HUB, ListSettings(context).todoHome())
    }

    @Test
    fun `a to-do moved in Settings stays where it was put`() {
        AlfredSettings(context).setHost("192.168.1.100:8200")

        ListSettings(context).setTodoHome(ListHome.PHONE)

        assertEquals(ListHome.PHONE, ListSettings(context).todoHome())
        assertEquals(ListHome.HUB, ListSettings(context).defaultHome())
    }

    @Test
    fun `an unknown stored home reads as the hub, where every list lived before`() {
        assertEquals(ListHome.HUB, ListHome.fromName("ELSEWHERE"))
        assertEquals(ListHome.HUB, ListHome.fromName(null))
    }
}
