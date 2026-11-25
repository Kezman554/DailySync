package com.personalmorningalarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.profile.ProfileSettings
import com.personalmorningalarm.util.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reschedules the alarm after a device restart. AlarmManager alarms are cleared
 * on reboot, so on BOOT_COMPLETED we read the saved config and, if the alarm is
 * enabled, schedule its next occurrence again.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (ProfileSettings(context).profile()?.hasAlarm != true) {
            Log.d(TAG, "Boot: this profile has no alarm — nothing to schedule")
            return
        }

        // DB read must happen off the main thread; keep the process alive for it.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = AlarmRepository(AppDatabase.getInstance(appContext))
                val config = repository.getCurrentConfig()
                if (config != null && config.isEnabled) {
                    val scheduled = AlarmScheduler(appContext).scheduleDailyAlarm(config.alarmTime)
                    Log.d(TAG, "Boot: rescheduled alarm (alarmTime=${config.alarmTime}, ok=$scheduled)")
                } else {
                    Log.d(TAG, "Boot: nothing to reschedule (config=$config)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Boot: failed to reschedule alarm", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PMA"
    }
}
