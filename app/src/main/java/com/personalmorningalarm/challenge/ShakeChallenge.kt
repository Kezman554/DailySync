package com.personalmorningalarm.challenge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Stage 1 challenge: dismiss by vigorously shaking the phone for a cumulative
 * [targetDurationMs] (default 15s). "Shaking" is when the acceleration magnitude
 * deviates from gravity by more than [shakeThreshold] m/s^2 — a force measure
 * that's independent of sensor sample rate (the old delta-between-samples method
 * broke when the accelerometer was throttled coming out of Doze) and of
 * orientation. Time only accrues while actively shaking; a short pause tolerance
 * bridges the brief low-motion instant at each shake reversal.
 */
class ShakeChallenge(
    context: Context,
    private val targetDurationMs: Long = DEFAULT_TARGET_DURATION_MS,
    private val shakeThreshold: Float = DEFAULT_SHAKE_THRESHOLD
) : ChallengeInterface, SensorEventListener {

    override var onProgress: ((Float) -> Unit)? = null
    override var onComplete: (() -> Unit)? = null

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Pure timing/threshold core (unit-tested in isolation).
    private val accumulator = ShakeAccumulator(targetDurationMs, shakeThreshold)

    private var running = false
    private var completed = false

    override fun start() {
        if (running) return
        val sensor = accelerometer
        if (sensor == null) {
            Log.w(TAG, "No accelerometer available; ShakeChallenge cannot run")
            return
        }
        // Re-baseline timing only — accumulated progress is preserved across
        // stop/start so a pause (e.g. switching shaking arms) doesn't lose it.
        accumulator.rebaseline()
        running = true
        // GAME rate (~50Hz) is plenty for force-based detection and needs no
        // permission. (FASTEST/0us requires HIGH_SAMPLING_RATE_SENSORS on API 31+
        // and crashed registerListener.)
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    /** Clears accumulated progress so the challenge can run from scratch. */
    fun reset() {
        completed = false
        accumulator.reset()
    }

    override fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        // Force measure: how far total acceleration is from gravity. ~0 at rest,
        // large during a shake, independent of sample rate and orientation.
        val linearAccel = abs(magnitude - GRAVITY)

        val progress = accumulator.onSample(linearAccel, System.currentTimeMillis())
        onProgress?.invoke(progress)

        if (!completed && accumulator.isComplete) {
            completed = true
            stop()
            onComplete?.invoke()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for shake detection.
    }

    companion object {
        private const val TAG = "PMA"

        /** Standard gravity (m/s^2); subtracted from the magnitude to get net force. */
        private const val GRAVITY = 9.80665f

        // Force threshold (m/s^2 deviation from gravity). Switched from the old
        // delta-between-samples method, which stalled when the sensor was
        // throttled out of Doze. 3.5f registers a moderate, comfortable shake.
        const val DEFAULT_SHAKE_THRESHOLD = 3.5f
        const val DEFAULT_TARGET_DURATION_MS = 15_000L
    }
}
