package com.personalmorningalarm.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.personalmorningalarm.data.profile.AlarmPermission
import com.personalmorningalarm.data.profile.ProfileSettings
import com.personalmorningalarm.receiver.AlarmReceiver
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Schedules and cancels exact alarms via [AlarmManager].
 *
 * Uses setExactAndAllowWhileIdle so alarms still fire when the device is in
 * Doze. On API 31+ exact alarms require the SCHEDULE_EXACT_ALARM permission;
 * callers should check [canScheduleExactAlarms] first and, if false, send the
 * user to [exactAlarmSettingsIntent]. On API 30 and below it is auto-granted.
 *
 * A profile without the alarm (Jess's) never schedules: [scheduleAlarm] refuses,
 * whoever calls it, so no path can arm an alarm on her phone.
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    /**
     * Schedules an exact alarm to fire at [triggerAtMillis] (wall-clock epoch ms).
     * Returns false if exact alarms aren't permitted (API 31+ without permission).
     */
    fun scheduleAlarm(triggerAtMillis: Long, requestCode: Int = DEFAULT_REQUEST_CODE): Boolean {
        if (!ProfileSettings(context).allows(AlarmPermission.EXACT_ALARM)) {
            Log.d(TAG, "Not scheduling: this profile has no alarm")
            return false
        }
        if (!canScheduleExactAlarms()) {
            Log.w(TAG, "Cannot schedule exact alarm: permission not granted")
            return false
        }
        // Non-null: FLAG_NO_CREATE is absent, so getBroadcast always returns an instance.
        val pendingIntent = buildPendingIntent(requestCode, PendingIntent.FLAG_UPDATE_CURRENT)!!
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pendingIntent
        )
        Log.d(TAG, "Alarm scheduled for $triggerAtMillis (requestCode=$requestCode)")
        return true
    }

    /**
     * Schedules the alarm for the next occurrence of a daily wall-clock time,
     * given as minutes since midnight (e.g. 06:30 -> 390). If that time has
     * already passed today, schedules it for tomorrow. Returns false if exact
     * alarms aren't permitted. Used both when saving the alarm and when
     * rescheduling after a reboot.
     */
    fun scheduleDailyAlarm(alarmTimeMinutes: Int, requestCode: Int = DEFAULT_REQUEST_CODE): Boolean {
        return scheduleAlarm(nextTriggerMillis(alarmTimeMinutes), requestCode)
    }

    /** Epoch millis of the next occurrence of [alarmTimeMinutes] (today or tomorrow). */
    fun nextTriggerMillis(
        alarmTimeMinutes: Int,
        from: LocalDateTime = LocalDateTime.now()
    ): Long {
        val time = LocalTime.of(alarmTimeMinutes / 60, alarmTimeMinutes % 60)
        var next = from.toLocalDate().atTime(time)
        if (!next.isAfter(from)) {
            next = next.plusDays(1)
        }
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun cancelAlarm(requestCode: Int = DEFAULT_REQUEST_CODE) {
        val pendingIntent = buildPendingIntent(requestCode, PendingIntent.FLAG_NO_CREATE)
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "Alarm cancelled (requestCode=$requestCode)")
        }
    }

    /**
     * Whether a pending alarm with [requestCode] currently exists. Note a
     * one-shot alarm's PendingIntent lingers after it fires until cancelled.
     */
    fun isAlarmScheduled(requestCode: Int = DEFAULT_REQUEST_CODE): Boolean =
        buildPendingIntent(requestCode, PendingIntent.FLAG_NO_CREATE) != null

    /** Intent to the system "Alarms & reminders" permission screen (API 31+), else null. */
    fun exactAlarmSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            null
        }

    private fun buildPendingIntent(requestCode: Int, extraFlags: Int): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_ALARM_FIRED
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or extraFlags
        )
    }

    companion object {
        private const val TAG = "PMA"
        const val DEFAULT_REQUEST_CODE = 1001
    }
}
