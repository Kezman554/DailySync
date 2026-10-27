package com.personalmorningalarm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.personalmorningalarm.R
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.model.AlarmSounds
import com.personalmorningalarm.ui.AlarmDismissalActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that drives the Stage 1 alarm: plays a looping alarm tone
 * and surfaces [AlarmDismissalActivity] over the lock screen via a
 * full-screen-intent notification (the reliable way to launch an activity from
 * the background on API 29+).
 *
 * Stage 1 does NOT vibrate: shake detection is force-based and would read the
 * vibration motor as "shaking" and auto-dismiss. Sound-only also keeps Stage 1
 * gentle (PRD: don't wake a partner). Strong vibration belongs to the nuclear alarm.
 */
class AlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "AlarmService starting (foreground)")
        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning = true
        // Read the chosen sound + volume off the main thread, then start playback.
        scope.launch {
            val config = AlarmRepository(AppDatabase.getInstance(this@AlarmService)).getCurrentConfig()
            val sound = AlarmSounds.stage1ByKey(config?.stage1SoundId)
            val volumePercent = (config?.stage1Volume ?: 100).coerceIn(0, 100)
            // Set the device alarm-stream level to the app-set volume so the alarm
            // starts at the configured loudness every morning regardless of where the
            // slider was left the previous day. The dismissal screen routes the
            // hardware volume buttons to STREAM_ALARM, so it can still be turned down
            // while shaking — but next fire resets it to this level.
            applyAlarmStreamVolume(volumePercent)
            startAlarmSound(sound)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "AlarmService stopping")
        isRunning = false
        scope.cancel()
        mediaPlayer?.run {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        // Full-screen intent: launches the dismissal activity directly when the
        // device is locked/asleep, otherwise shows as a heads-up notification.
        // No CLEAR_TASK: with the activity's singleTask launch mode, re-launching
        // (e.g. tapping the notification mid-shake) reuses the existing instance
        // instead of recreating it and resetting shake progress.
        val fullScreenIntent = Intent(this, AlarmDismissalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            0,
            fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.alarm_notification_title))
            .setContentText(getString(R.string.alarm_notification_text))
            .setSmallIcon(R.drawable.ic_stat_dailysync)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build()
    }

    /** Sets STREAM_ALARM to [percent] (0-100) of its maximum. */
    private fun applyAlarmStreamVolume(percent: Int) {
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val level = Math.round(percent / 100f * max).coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_ALARM, level, 0)
            Log.d(TAG, "Set alarm stream to $level/$max ($percent%)")
        }.onFailure { Log.w(TAG, "Failed to set alarm stream volume", it) }
    }

    private fun startAlarmSound(sound: com.personalmorningalarm.data.model.AlarmSound) {
        try {
            mediaPlayer = MediaPlayer().apply {
                AlarmSounds.setDataSource(this, this@AlarmService, sound)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                // Loudness is governed by the alarm stream level (set above); play at
                // full MediaPlayer scale so the stream level is the single source of truth.
                setVolume(1f, 1f)
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alarm sound", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.alarm_channel_description)
                setSound(null, null) // sound handled by MediaPlayer, not the channel
                enableVibration(false) // vibration handled by the service
                setBypassDnd(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "PMA"
        private const val CHANNEL_ID = "alarm_channel"
        private const val NOTIFICATION_ID = 2001
        const val ACTION_STOP = "com.personalmorningalarm.action.STOP_ALARM"

        /** True while the alarm is actively sounding. */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, AlarmService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AlarmService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
