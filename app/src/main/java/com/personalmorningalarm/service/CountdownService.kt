package com.personalmorningalarm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.personalmorningalarm.R
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Foreground service that runs the Stage 2 countdown. Ticks once per second,
 * publishing the remaining seconds via [remainingSeconds] and a notification
 * (tap opens the app). Duration comes from the start intent's
 * [EXTRA_DURATION_SECONDS], or falls back to [AlarmConfig]'s stage2 duration
 * (default 10 minutes). On expiry it broadcasts [ACTION_COUNTDOWN_EXPIRED]
 * (the future nuclear alarm will listen for this).
 */
class CountdownService : Service() {

    private var timer: CountDownTimer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pauseTimer()
                return START_STICKY
            }
            ACTION_RESUME -> {
                resumeTimer()
                return START_STICKY
            }
        }

        val explicitSeconds = intent?.getIntExtra(EXTRA_DURATION_SECONDS, -1) ?: -1
        val initialSeconds =
            if (explicitSeconds > 0) explicitSeconds else DEFAULT_DURATION_MINUTES * 60
        startForeground(NOTIFICATION_ID, buildNotification(initialSeconds))
        isRunning = true

        if (explicitSeconds > 0) {
            Log.d(TAG, "CountdownService starting ($explicitSeconds s, explicit)")
            startTimer(explicitSeconds)
        } else {
            // No duration supplied — read it from the saved config.
            serviceScope.launch {
                val minutes = withContext(Dispatchers.IO) {
                    AlarmRepository(AppDatabase.getInstance(this@CountdownService))
                        .getCurrentConfig()?.stage2DurationMinutes
                } ?: DEFAULT_DURATION_MINUTES
                Log.d(TAG, "CountdownService starting (${minutes * 60} s, from config)")
                startTimer(minutes * 60)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "CountdownService stopping")
        isRunning = false
        timer?.cancel()
        timer = null
        _remainingSeconds.value = 0
        serviceScope.cancel()
        super.onDestroy()
    }

    /** Freezes the countdown (e.g. while a content screen is showing). */
    private fun pauseTimer() {
        timer?.cancel()
        timer = null
        Log.d(TAG, "Countdown paused at ${_remainingSeconds.value}s")
    }

    /** Resumes from the frozen remaining time. */
    private fun resumeTimer() {
        if (timer != null) return // already running
        val remaining = _remainingSeconds.value
        if (remaining > 0) {
            Log.d(TAG, "Countdown resumed at ${remaining}s")
            startTimer(remaining)
        }
    }

    private fun startTimer(totalSeconds: Int) {
        _remainingSeconds.value = totalSeconds
        timer?.cancel()
        timer = object : CountDownTimer(totalSeconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000L).toInt()
                _remainingSeconds.value = seconds
                updateNotification(seconds)
            }

            override fun onFinish() {
                _remainingSeconds.value = 0
                Log.d(TAG, "Stage 2 countdown expired — triggering nuclear alarm")
                // Broadcast so the Stage 2 UI can finish itself...
                sendBroadcast(Intent(ACTION_COUNTDOWN_EXPIRED).setPackage(packageName))
                // ...and start the nuclear alarm directly (reliable, no receiver hop).
                NuclearAlarmService.start(this@CountdownService)
                stopSelf()
            }
        }.start()
    }

    private fun buildNotification(seconds: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.countdown_notification_title))
            .setContentText(formatTime(seconds))
            .setSmallIcon(R.drawable.ic_stat_dailysync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(appPendingIntent())
            .build()

    private fun updateNotification(seconds: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(seconds))
    }

    /** Tapping the notification opens the app. */
    private fun appPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.countdown_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.countdown_channel_description)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "PMA"
        private const val CHANNEL_ID = "countdown_channel"
        private const val NOTIFICATION_ID = 3001
        private const val DEFAULT_DURATION_MINUTES = 10

        const val EXTRA_DURATION_SECONDS = "extra_duration_seconds"
        const val ACTION_STOP = "com.personalmorningalarm.action.STOP_COUNTDOWN"
        const val ACTION_PAUSE = "com.personalmorningalarm.action.PAUSE_COUNTDOWN"
        const val ACTION_RESUME = "com.personalmorningalarm.action.RESUME_COUNTDOWN"
        const val ACTION_COUNTDOWN_EXPIRED = "com.personalmorningalarm.action.COUNTDOWN_EXPIRED"

        private val _remainingSeconds = MutableStateFlow(0)

        /** Seconds left in the Stage 2 countdown; 0 when not running. */
        val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Start with an explicit duration. Omit to read it from AlarmConfig. */
        fun start(context: Context, durationSeconds: Int? = null) {
            val intent = Intent(context, CountdownService::class.java).apply {
                durationSeconds?.let { putExtra(EXTRA_DURATION_SECONDS, it) }
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            sendAction(context, ACTION_STOP)
        }

        /** Pause the countdown (call while a content screen is showing). */
        fun pause(context: Context) {
            sendAction(context, ACTION_PAUSE)
        }

        /** Resume the countdown from where it was paused. */
        fun resume(context: Context) {
            sendAction(context, ACTION_RESUME)
        }

        private fun sendAction(context: Context, action: String) {
            val intent = Intent(context, CountdownService::class.java).apply {
                this.action = action
            }
            context.startService(intent)
        }

        fun formatTime(totalSeconds: Int): String {
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }
}
