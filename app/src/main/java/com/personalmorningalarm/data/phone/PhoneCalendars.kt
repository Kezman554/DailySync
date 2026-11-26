package com.personalmorningalarm.data.phone

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import com.personalmorningalarm.data.model.PhoneEvent
import com.personalmorningalarm.util.ThemeManager

/** A calendar synced on this phone that events can be written to. */
data class PhoneCalendar(
    val id: Long,
    /** What the phone's calendar app calls it. */
    val name: String,
    /** The Google account it belongs to. */
    val account: String,
    /** The calendar's own name on the server, which a local rename doesn't change. */
    val serverName: String = name
)

/** Which phone calendar a "My calendar" event goes in. */
sealed interface PhoneCalendarPick {
    data class Use(val calendar: PhoneCalendar) : PhoneCalendarPick
    /** Several, and none chosen yet (or the chosen one has gone): the person picks. */
    data class Ask(val calendars: List<PhoneCalendar>) : PhoneCalendarPick
    /** No writable Google calendar on this phone. */
    data object None : PhoneCalendarPick
}

/** The choosing rules, apart from the content provider so they can be tested. */
object PhoneCalendarChoice {

    /**
     * The family calendar's name. If it's synced to this phone it is left out of the
     * list, so a family event can only take the hub's path, which adds the name
     * prefix the wall colours by.
     */
    const val FAMILY_CALENDAR_NAME = "Young Family"

    fun offered(calendars: List<PhoneCalendar>): List<PhoneCalendar> =
        calendars.filterNot { isFamily(it.name) || isFamily(it.serverName) }

    private fun isFamily(name: String): Boolean = name.trim().equals(FAMILY_CALENDAR_NAME, ignoreCase = true)

    /**
     * The remembered calendar if it's still offered; otherwise the only one; otherwise
     * ask. A remembered id from another phone (restored from backup) matches nothing
     * here, so it asks again.
     */
    fun resolve(calendars: List<PhoneCalendar>, rememberedId: Long?): PhoneCalendarPick {
        val offered = offered(calendars)
        offered.firstOrNull { it.id == rememberedId }?.let { return PhoneCalendarPick.Use(it) }
        return when (offered.size) {
            0 -> PhoneCalendarPick.None
            1 -> PhoneCalendarPick.Use(offered.single())
            else -> PhoneCalendarPick.Ask(offered)
        }
    }
}

/** The phone's calendars, and which one "My calendar" means. Needs READ/WRITE_CALENDAR. */
interface PhoneCalendarStore {
    /** Every writable, synced Google calendar on the phone, the family one included. */
    fun calendars(): List<PhoneCalendar>

    /** Writes [event] to calendar [calendarId]; false if the provider wouldn't take it. */
    fun insert(calendarId: Long, event: PhoneEvent): Boolean

    fun remembered(): Long?

    fun remember(calendarId: Long)
}

/** [PhoneCalendarStore] over CalendarContract. Nothing here goes to the Pi. */
class AndroidPhoneCalendars(context: Context) : PhoneCalendarStore {

    private val resolver = context.applicationContext.contentResolver
    private val prefs = context.applicationContext
        .getSharedPreferences(ThemeManager.PREFS_NAME, Context.MODE_PRIVATE)

    override fun calendars(): List<PhoneCalendar> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.NAME
        )
        val selection = "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND " +
            "${CalendarContract.Calendars.SYNC_EVENTS} = 1"
        val args = arrayOf(GOOGLE_ACCOUNT_TYPE, CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val calendars = mutableListOf<PhoneCalendar>()
        try {
            resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1).orEmpty()
                    calendars += PhoneCalendar(
                        id = c.getLong(0),
                        name = name,
                        account = c.getString(2).orEmpty(),
                        serverName = c.getString(3) ?: name
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar list without permission", e)
        }
        return calendars.sortedWith(compareBy({ it.account }, { it.name.lowercase() }))
    }

    override fun insert(calendarId: Long, event: PhoneEvent): Boolean {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DTSTART, event.startMillis)
            put(CalendarContract.Events.DTEND, event.endMillis)
            put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, event.timeZone)
        }
        return try {
            resolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
        } catch (e: Exception) {
            Log.w(TAG, "Phone calendar insert failed", e)
            false
        }
    }

    override fun remembered(): Long? =
        prefs.getLong(KEY_CALENDAR_ID, NONE).takeIf { it != NONE }

    override fun remember(calendarId: Long) {
        prefs.edit().putLong(KEY_CALENDAR_ID, calendarId).apply()
    }

    private companion object {
        const val TAG = "PMA"
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        const val KEY_CALENDAR_ID = "my_calendar_id"
        const val NONE = -1L
    }
}
