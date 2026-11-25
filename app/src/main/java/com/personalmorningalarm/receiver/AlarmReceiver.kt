package com.personalmorningalarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.profile.ProfileSettings
import com.personalmorningalarm.service.AlarmService
import com.personalmorningalarm.service.NuclearAlarmService
import com.personalmorningalarm.ui.AlarmDismissalActivity
import com.personalmorningalarm.util.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

/**
 * Fired by AlarmManager when a scheduled alarm goes off. Starts the Stage 1
 * foreground [AlarmService], which plays the alarm and shows the dismissal UI,
 * then re-arms the alarm for the next day (exact alarms are one-shot).
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // Only an alarm armed before a switch to a profile without one can get here.
        if (ProfileSettings(context).profile()?.hasAlarm != true) {
            Log.d(TAG, "Alarm fired but this profile has no alarm — ignoring, and not re-arming")
            AlarmScheduler(context).cancelAlarm()
            return
        }
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        if (AlarmDismissalActivity.isSessionActive || NuclearAlarmService.isRunning) {
            Log.d(TAG, "Alarm fired at $now but a wake session/nuclear alarm is active — ignoring")
        } else {
            Log.d(TAG, "Alarm fired at $now (action=${intent?.action}) — starting AlarmService")
            // Start the foreground service promptly while we hold the broadcast's
            // brief FGS-start allowance; the DB-backed re-arm happens async below.
            AlarmService.start(context)
        }
        rescheduleNextDay(context)
    }

    /**
     * Re-arms the alarm for its next occurrence. AlarmManager exact alarms are
     * one-shot, so without this the alarm fires once and never again (until a
     * reboot's [BootReceiver] or a manual save re-schedules it). Runs even when
     * the fire was ignored, so an overlapping session still gets tomorrow set up.
     */
    private fun rescheduleNextDay(context: Context) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = AlarmRepository(AppDatabase.getInstance(appContext))
                val config = repository.getCurrentConfig()
                if (config != null && config.isEnabled) {
                    // +1 min so today's just-fired time rolls forward to tomorrow
                    // rather than re-arming a few seconds from now.
                    val scheduler = AlarmScheduler(appContext)
                    val next = scheduler.nextTriggerMillis(
                        config.alarmTime,
                        LocalDateTime.now().plusMinutes(1)
                    )
                    val ok = scheduler.scheduleAlarm(next)
                    Log.d(TAG, "Re-armed next alarm (alarmTime=${config.alarmTime}, ok=$ok)")
                } else {
                    Log.d(TAG, "Not re-arming: alarm disabled or no config ($config)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-arm next alarm", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PMA"
        const val ACTION_ALARM_FIRED = "com.personalmorningalarm.action.ALARM_FIRED"
    }
}
