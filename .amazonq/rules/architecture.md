# Architecture Rules — InactivityReminder (Wear OS)

## App Purpose
A Wear OS app for Samsung Galaxy Watch 5 Pro that detects physical inactivity via
the accelerometer and fires a user-configurable reminder notification (default 30 min,
range 10–60 min), unlike the OS-level Stand reminder which is fixed at 50 min and not
user-adjustable.

## Layered Architecture — always follow this separation

```
presentation/   → Compose UI only. No sensor or service logic here.
service/        → InactivityForegroundService. Orchestrates sensor + notifications.
sensor/         → MotionDetector. Wraps SensorManager. No UI or notification code here.
notification/   → ReminderNotifier. Builds and fires notifications only.
data/           → SettingsRepository (DataStore). Single source of truth for user prefs.
```

**Rule:** Never let `presentation/` talk directly to `SensorManager` or `NotificationManager`.
UI only reads/writes through `SettingsRepository` (Flow) and starts/stops the service.

## Core Components

### SettingsRepository (data/)
- Backed by Jetpack DataStore<Preferences> — not SharedPreferences.
- Exposes: `inactivityIntervalMinutes: Flow<Int>` (default 30), `isMonitoringEnabled: Flow<Boolean>`.
- Single source of truth — both UI and service observe this, never duplicate state.

### MotionDetector (sensor/)
- Wraps `SensorManager` + `SensorEventListener` for `Sensor.TYPE_ACCELEROMETER`.
- Use `SensorManager.SENSOR_DELAY_NORMAL` (battery-conscious, no need for high frequency).
- Tracks `lastMovementTimestamp`, exposes it via callback or Flow.
- Also wraps `Sensor.TYPE_OFFBODY_DETECTION` to suppress checks when watch isn't worn.

### InactivityForegroundService (service/)
- Foreground service type: `health` (NOT `none` or default — required for Wear OS 6 / Android 14+,
  wrong type causes a runtime crash).
- Runs a coroutine loop (~60s interval) comparing `now - lastMovementTimestamp` against the
  user's configured interval from SettingsRepository.
- On threshold exceeded → calls ReminderNotifier, then resets timer.
- `onStartCommand` returns `START_STICKY` for resilience.

### ReminderNotifier (notification/)
- Two notification channels:
  1. Silent/ongoing — "Monitoring activity" persistent foreground notification.
  2. Alerting — the actual inactivity reminder, with vibration + a "Snooze 10 min" action.

## Permissions Required
```xml
<uses-permission android:name="android.permission.BODY_SENSORS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-feature android:name="android.hardware.sensor.accelerometer" android:required="true" />
```

## Known Constraints (do not violate these when generating code)
- Wear OS suspends background sensor listeners outside a foreground service — never suggest
  a plain background Service or WorkManager-only approach for continuous motion sensing.
- `BODY_SENSORS` and `POST_NOTIFICATIONS` are runtime permissions — always include the
  request flow in MainActivity, never assume granted.
- Not all sensors returned by `SensorManager.getSensorList()` are accessible to third-party
  apps on Samsung devices. Stick to accelerometer, gyroscope, and off-body detection —
  these are confirmed accessible.
