package com.example.inactivityreminder.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * MotionDetector — Wraps the system SensorManager to detect physical movement.
 *
 * Responsibilities:
 *   1. Listen to TYPE_ACCELEROMETER to detect whether the user is moving.
 *   2. Listen to TYPE_LOW_LATENCY_OFFBODY_DETECT to know if the watch is being worn.
 *   3. Maintain [lastMovementTimestamp] — the epoch time of the most recent detected movement.
 *   4. Log state transitions (active ↔ inactive) for debugging.
 *
 * This class does NOT interact with notifications or UI — it purely tracks sensor data.
 * The foreground service reads [lastMovementTimestamp] periodically to decide whether
 * to fire an inactivity reminder.
 *
 * @param context Used to obtain the system SensorManager service.
 */
class MotionDetector(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "MotionDetector"

        /**
         * Minimum deviation from gravity (9.81 m/s²) required to count as "movement".
         * On physical watches, wrist micro-tremors can produce deltas of 2-4 m/s².
         * A value of 5.0 requires deliberate, obvious motion (walking, arm swing)
         * to register as active. Check Logcat "Accel sample" logs to see actual
         * deltas on your device and tune this value accordingly.
         */
        private const val MOVEMENT_THRESHOLD = 5.0f
    }

    /** System sensor manager — gateway to all hardware sensors. */
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /**
     * Accelerometer sensor. Returns 3-axis acceleration including gravity.
     * Null if device has no accelerometer (shouldn't happen on Galaxy Watch 5 Pro).
     */
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /**
     * Off-body detection sensor. Returns 1.0 when on wrist, 0.0 when removed.
     * TYPE_LOW_LATENCY_OFFBODY_DETECT (type 34) is preferred; falls back to type 21.
     * Null if neither sensor is available on the device.
     */
    private val offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        ?: sensorManager.getDefaultSensor(21)

    /**
     * Epoch timestamp (ms) of the last detected movement.
     * The service compares (now - this) against the user's configured interval.
     * Marked @Volatile because the service reads it from a coroutine (different thread)
     * while the sensor callback writes it on the sensor thread.
     */
    @Volatile
    var lastMovementTimestamp: Long = System.currentTimeMillis()
        private set

    /**
     * Whether the watch is currently on the user's body.
     * When false, the service skips inactivity checks (no point alerting if watch is on a table).
     */
    @Volatile
    var isOnBody: Boolean = true
        private set

    /**
     * Tracks whether the user is currently in an "active" state (moving).
     * Used for logging state transitions and updating the status notification.
     */
    @Volatile
    var isCurrentlyActive: Boolean = true
        private set

    /** Guard to prevent double-registering sensor listeners. */
    private var isListening = false

    /** Counter for periodic logging of sensor samples (avoids flooding Logcat). */
    private var sampleCount = 0

    /**
     * Registers sensor listeners and begins tracking movement.
     * Uses SENSOR_DELAY_NORMAL (~200ms interval) which is battery-efficient
     * and sufficient for detecting general motion vs. stillness.
     */
    fun start() {
        if (isListening) return
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        offBodySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        // Initialize timestamp to now so the first check doesn't immediately trigger
        lastMovementTimestamp = System.currentTimeMillis()
        isCurrentlyActive = true
        isListening = true
        Log.d(TAG, "Started listening. Accelerometer=${accelerometer != null}, OffBody=${offBodySensor != null}")
    }

    /**
     * Unregisters all sensor listeners to stop receiving events.
     * Called when the foreground service is destroyed.
     */
    fun stop() {
        sensorManager.unregisterListener(this)
        isListening = false
        Log.d(TAG, "Stopped listening")
    }

    /**
     * Resets the inactivity timer to "now".
     * Called after a reminder fires (so the next check starts fresh)
     * or when the user taps "Snooze".
     */
    fun resetTimer() {
        lastMovementTimestamp = System.currentTimeMillis()
        Log.d(TAG, "Timer reset")
    }

    /**
     * Logs the current inactivity status for debugging.
     * Called by the service on each check cycle so you can see in Logcat
     * how close the user is to triggering a reminder.
     *
     * @param elapsedMs Milliseconds since last detected movement.
     * @param thresholdMs Configured inactivity threshold in milliseconds.
     */
    fun logInactivityStatus(elapsedMs: Long, thresholdMs: Long) {
        val elapsedSec = elapsedMs / 1000
        val thresholdSec = thresholdMs / 1000
        if (elapsedMs >= thresholdMs) {
            Log.w(TAG, "⚠️ INACTIVE for ${elapsedSec}s (threshold: ${thresholdSec}s) — triggering reminder")
        } else {
            Log.d(TAG, "Inactive for ${elapsedSec}s / ${thresholdSec}s threshold — not yet triggered")
        }
    }

    /**
     * Called by the system whenever a registered sensor produces new data.
     * Handles two sensor types:
     *   - Accelerometer: computes magnitude, compares to gravity to detect movement.
     *   - Off-body: updates [isOnBody] flag.
     */
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                val delta = Math.abs(magnitude - 9.81f)

                // Log every 50th reading so we can see actual values on physical device
                sampleCount++
                if (sampleCount % 50 == 0) {
                    val elapsed = (System.currentTimeMillis() - lastMovementTimestamp) / 1000
                    Log.d(TAG, "Accel sample #$sampleCount: delta=${"%05.2f".format(delta)} | threshold=$MOVEMENT_THRESHOLD | inactive=${elapsed}s")
                }

                if (delta > MOVEMENT_THRESHOLD) {
                    if (!isCurrentlyActive) {
                        Log.i(TAG, "🟢 MOVEMENT DETECTED — user became active (delta=${"%.2f".format(delta)})")
                        isCurrentlyActive = true
                    }
                    lastMovementTimestamp = System.currentTimeMillis()
                } else {
                    if (isCurrentlyActive) {
                        val inactiveSec = (System.currentTimeMillis() - lastMovementTimestamp) / 1000
                        if (inactiveSec > 5) {
                            Log.i(TAG, "🔴 NO MOVEMENT — user became inactive (still for ${inactiveSec}s)")
                            isCurrentlyActive = false
                        }
                    }
                }
            }
            34, 21 -> {
                isOnBody = event.values[0] != 0.0f
                Log.d(TAG, "On-body: $isOnBody")
            }
        }
    }

    /** Required by SensorEventListener but not needed for this use case. */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
