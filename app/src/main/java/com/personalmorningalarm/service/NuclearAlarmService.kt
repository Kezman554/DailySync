package com.personalmorningalarm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.personalmorningalarm.R
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.model.AlarmSounds
import com.personalmorningalarm.ui.NuclearDismissalActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The nuclear alarm: Stage 2's failure state. Triggered when the Stage 2
 * countdown expires before all NFC checkpoints are tapped. Unlike Stage 1's
 * gentle [AlarmService], this is meant to be impossible to ignore — loud,
 * strong vibration, and a flashlight strobe — and surfaces
 * [NuclearDismissalActivity] over the lock screen via a full-screen intent.
 *
 * Sound is deliberately distinct from Stage 1: Stage 1 uses the default ALARM
 * tone, nuclear uses the default RINGTONE tone (different audio), both routed
 * through USAGE_ALARM so they ride the alarm volume stream.
 *
 * VOLUME / TESTING: with [FORCE_MAX_VOLUME] false (default), loudness is left to
 * the device's alarm-volume slider — so it can be turned down for testing. Set
 * it true for production so nuclear forces the alarm stream to maximum.
 */
class NuclearAlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val strobeHandler = Handler(Looper.getMainLooper())
    private var cameraManager: CameraManager? = null
    private var strobeCameraId: String? = null
    private var torchOn = false
    private val strobeRunnable = object : Runnable {
        override fun run() {
            toggleTorch()
            strobeHandler.postDelayed(this, STROBE_INTERVAL_MS)
        }
    }

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

        Log.d(TAG, "NuclearAlarmService starting (foreground)")
        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning = true
        if (STROBE_ENABLED) startStrobe()
        // Read the chosen sound + vibration setting off the main thread.
        scope.launch {
            val config =
                AlarmRepository(AppDatabase.getInstance(this@NuclearAlarmService)).getCurrentConfig()
            startNuclearSound(AlarmSounds.nuclearByKey(config?.nuclearSoundId))
            if (config?.vibrationEnabled != false) startVibration()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "NuclearAlarmService stopping")
        isRunning = false
        scope.cancel()
        mediaPlayer?.run {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        vibrator?.cancel()
        stopStrobe()
        super.onDestroy()
    }

    // --- Sound ---

    private fun startNuclearSound(sound: com.personalmorningalarm.data.model.AlarmSound) {
        if (FORCE_MAX_VOLUME) forceMaxAlarmVolume()

        try {
            mediaPlayer = MediaPlayer().apply {
                AlarmSounds.setDataSource(this, this@NuclearAlarmService, sound)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                setVolume(1f, 1f) // full MediaPlayer scale; stream level still applies
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start nuclear sound", e)
        }
    }

    /** Production-only: pin the alarm stream to its maximum. Gated by [FORCE_MAX_VOLUME]. */
    private fun forceMaxAlarmVolume() {
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            Log.d(TAG, "Forced alarm stream to max ($max)")
        }
    }

    // --- Vibration ---

    private fun startVibration() {
        vibrator = resolveVibrator()
        val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, VIBRATION_AMPLITUDES, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_ALARM)
                .build()
            vibrator?.vibrate(effect, attrs)
        } else {
            vibrator?.vibrate(effect)
        }
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    // --- Flashlight strobe ---

    private fun startStrobe() {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        strobeCameraId = runCatching {
            cameraManager?.cameraIdList?.firstOrNull { id ->
                cameraManager?.getCameraCharacteristics(id)
                    ?.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()

        if (strobeCameraId == null) {
            Log.d(TAG, "No flash-capable camera — skipping strobe")
            return
        }
        strobeHandler.post(strobeRunnable)
    }

    private fun toggleTorch() {
        val id = strobeCameraId ?: return
        torchOn = !torchOn
        runCatching { cameraManager?.setTorchMode(id, torchOn) }
            .onFailure { Log.w(TAG, "Strobe toggle failed", it) }
    }

    private fun stopStrobe() {
        strobeHandler.removeCallbacks(strobeRunnable)
        val id = strobeCameraId ?: return
        runCatching { cameraManager?.setTorchMode(id, false) }
        torchOn = false
    }

    // --- Notification ---

    private fun buildNotification(): Notification {
        val fullScreenIntent = Intent(this, NuclearDismissalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            0,
            fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.nuclear_notification_title))
            .setContentText(getString(R.string.nuclear_notification_text))
            .setSmallIcon(R.drawable.ic_stat_dailysync)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true) // can't be swiped away while the FGS runs
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.nuclear_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.nuclear_channel_description)
                setSound(null, null) // sound handled by MediaPlayer
                enableVibration(false) // vibration handled by the service
                setBypassDnd(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "PMA"
        private const val CHANNEL_ID = "nuclear_channel"
        private const val NOTIFICATION_ID = 4001
        const val ACTION_STOP = "com.personalmorningalarm.action.STOP_NUCLEAR"

        /**
         * Forces the alarm stream to maximum so the nuclear alarm cannot be quietened.
         * Combined with NuclearDismissalActivity swallowing the volume keys, the nuclear
         * alarm can't be turned down or muted with the hardware buttons. TESTING NOTE:
         * this is genuinely loud — test where it won't wake the household, or flip this
         * to false temporarily (the in-app slider does NOT affect nuclear).
         */
        private const val FORCE_MAX_VOLUME = true

        /** Flashlight strobe is silent, so it stays on during testing. */
        private const val STROBE_ENABLED = true
        private const val STROBE_INTERVAL_MS = 250L

        // Strong, relentless buzz: 600ms on / 400ms off, repeated (repeat index 0).
        private val VIBRATION_PATTERN = longArrayOf(0, 600, 400)
        private val VIBRATION_AMPLITUDES = intArrayOf(0, 255, 0)

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, NuclearAlarmService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, NuclearAlarmService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
