package com.example.inactivityreminder.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.inactivityreminder.R
import com.example.inactivityreminder.service.InactivityForegroundService

/**
 * ReminderNotifier — Responsible for building and posting all notifications.
 *
 * Manages two distinct notification channels:
 *   1. CHANNEL_MONITORING (low importance) — The silent, ongoing notification required
 *      by Android to keep the foreground service alive. Shown in the notification shade
 *      but doesn't alert/vibrate.
 *   2. CHANNEL_REMINDER (high importance) — The actual inactivity alert that vibrates
 *      and shows a "Time to move!" message with a "Snooze 10 min" action button.
 *
 * Two channels are necessary because Android ties importance/behavior to the channel,
 * not individual notifications. A single channel can't be both silent-ongoing AND alerting.
 *
 * @param context Used to access NotificationManager and build PendingIntents.
 */
class ReminderNotifier(private val context: Context) {

    companion object {
        private const val TAG = "ReminderNotifier"

        /** Channel ID for the persistent "Monitoring activity" notification. */
        const val CHANNEL_MONITORING = "monitoring_channel"

        /** Channel ID for the high-priority inactivity reminder alert. */
        const val CHANNEL_REMINDER = "reminder_channel"

        /** Notification ID for the ongoing foreground service notification. */
        const val NOTIFICATION_ID_MONITORING = 1

        /** Notification ID for the inactivity reminder alert. */
        const val NOTIFICATION_ID_REMINDER = 2

        /** Intent action string used to identify snooze requests sent to the service. */
        const val ACTION_SNOOZE = "com.example.inactivityreminder.SNOOZE"
    }

    /** System notification manager for posting and managing notifications. */
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        // Create channels on construction. This is safe to call multiple times —
        // Android ignores re-creation if the channel already exists.
        createChannels()
    }

    /**
     * Creates the two notification channels required by the app.
     * Must be called before posting any notification (done in init{}).
     * On Android 8.0+ (API 26+), notifications won't appear without a channel.
     */
    private fun createChannels() {
        // Silent channel for the ongoing foreground service notification
        val monitoring = NotificationChannel(
            CHANNEL_MONITORING, "Activity Monitoring",
            NotificationManager.IMPORTANCE_LOW  // Low = no sound, no vibration, shows in shade
        ).apply { description = "Ongoing notification while monitoring activity" }

        // Alerting channel for the actual inactivity reminder
        val reminder = NotificationChannel(
            CHANNEL_REMINDER, "Inactivity Reminder",
            NotificationManager.IMPORTANCE_HIGH  // High = heads-up display, sound, vibration
        ).apply {
            description = "Alerts when you've been inactive too long"
            enableVibration(true)
        }

        notificationManager.createNotificationChannels(listOf(monitoring, reminder))
    }

    /**
     * Builds the ongoing notification used by startForeground().
     * This notification is required by Android — without it, the foreground service
     * will be killed after ~10 seconds. It stays visible as long as the service runs.
     *
     * @return A built Notification object ready to pass to Service.startForeground().
     */
    fun buildMonitoringNotification(): android.app.Notification {
        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Monitoring activity")
            .setContentText("Tracking movement to remind you to stay active")
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * Updates the ongoing monitoring notification with current activity state.
     * Called periodically (every ~60s) by the service to show live status
     * without creating a new notification — reuses NOTIFICATION_ID_MONITORING.
     *
     * @param isActive Whether the user is currently moving.
     * @param inactiveSec Seconds since last detected movement.
     * @param thresholdSec Configured threshold in seconds.
     */
    fun updateStatusNotification(isActive: Boolean, inactiveSec: Long, thresholdSec: Long) {
        val state = if (isActive) "🟢 Active" else "🔴 Inactive"
        val text = "$state | Still for ${inactiveSec}s / ${thresholdSec}s threshold"

        val notification = NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Inactivity Monitor")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_MONITORING, notification)
    }

    /**
     * Posts the inactivity reminder notification that alerts the user.
     * Includes a "Snooze 10 min" action that sends an intent back to the service
     * to reset the inactivity timer for an additional 10 minutes.
     *
     * This is called by the service when (now - lastMovement) exceeds the threshold.
     */
    fun fireReminderNotification() {
        Log.w(TAG, "🔔 Posting inactivity reminder notification (ID=$NOTIFICATION_ID_REMINDER)")

        // Build an intent that will be sent to the service when "Snooze" is tapped
        val snoozeIntent = Intent(context, InactivityForegroundService::class.java).apply {
            action = ACTION_SNOOZE
        }
        // Wrap in a PendingIntent so the system can fire it on behalf of our app
        // FLAG_IMMUTABLE required for targetSdk 31+
        val snoozePending = PendingIntent.getService(
            context, 0, snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Time to move!")
            .setContentText("You've been inactive. Stand up and stretch.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)  // Ensures heads-up on older APIs
            .setAutoCancel(true)  // Dismiss when tapped
            .addAction(0, "Snooze 10 min", snoozePending)  // Action button
            .build()

        notificationManager.notify(NOTIFICATION_ID_REMINDER, notification)
    }
}
