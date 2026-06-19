package com.example.inactivityreminder.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.inactivityreminder.data.SettingsRepository
import com.example.inactivityreminder.notification.ReminderNotifier
import com.example.inactivityreminder.sensor.MotionDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * InactivityForegroundService — Orchestrates sensor + notifications.
 *
 * Uses a simple coroutine loop with a partial wake lock to keep the CPU alive.
 * The wake lock ensures the loop keeps running even when the screen is off.
 */
class InactivityForegroundService : Service() {

    companion object {
        private const val TAG = "InactivityService"
        private const val CHECK_INTERVAL_MS = 15_000L // 15s for testing
        private const val SNOOZE_MINUTES = 10
        private const val WAKELOCK_TAG = "InactivityReminder::ServiceWakeLock"
    }

    private lateinit var motionDetector: MotionDetector
    private lateinit var notifier: ReminderNotifier
    private lateinit var settingsRepository: SettingsRepository
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopStarted = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        motionDetector = MotionDetector(this)
        notifier = ReminderNotifier(this)
        settingsRepository = SettingsRepository(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand — action: ${intent?.action}")

        // Handle snooze action
        if (intent?.action == ReminderNotifier.ACTION_SNOOZE) {
            Log.d(TAG, "Snooze triggered — adding $SNOOZE_MINUTES min")
            motionDetector.resetTimer()
            return START_STICKY
        }

        // Promote to foreground
        try {
            startForeground(
                ReminderNotifier.NOTIFICATION_ID_MONITORING,
                notifier.buildMonitoringNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
            Log.d(TAG, "startForeground succeeded")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground FAILED: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // Acquire wake lock to keep CPU alive
        acquireWakeLock()

        // Start sensors
        motionDetector.start()

        // Start check loop (only once, guard against multiple onStartCommand calls)
        if (!loopStarted) {
            loopStarted = true
            startCheckLoop()
        }

        Log.d(TAG, "Service fully started")
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "Wake lock acquired (indefinite)")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
            Log.d(TAG, "Wake lock released")
        }
    }

    /**
     * Simple coroutine loop. Checks inactivity every CHECK_INTERVAL_MS.
     * Wake lock keeps CPU alive so delay() doesn't freeze.
     */
    private fun startCheckLoop() {
        scope.launch {
            while (true) {
                delay(CHECK_INTERVAL_MS)

                val intervalMin = settingsRepository.inactivityIntervalMinutes.first()
                val elapsed = System.currentTimeMillis() - motionDetector.lastMovementTimestamp
                val thresholdMs = intervalMin * 60_000L
                val isActive = motionDetector.isCurrentlyActive

                Log.d(TAG, "CHECK: active=$isActive | inactive=${elapsed / 1000}s / ${thresholdMs / 1000}s | onBody=${motionDetector.isOnBody}")

                // Update DataStore so the UI can display elapsed time (low frequency = battery friendly)
                settingsRepository.updateActivityStatus(elapsed / 1000, isActive)

                if (elapsed >= thresholdMs) {
                    Log.w(TAG, "🔔 FIRING REMINDER — inactive ${elapsed / 1000}s >= threshold ${thresholdMs / 1000}s")
                    notifier.fireReminderNotification()
                    motionDetector.resetTimer()
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — cleaning up")
        motionDetector.stop()
        scope.cancel()
        releaseWakeLock()
        loopStarted = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
