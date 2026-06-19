# Coding Standards — InactivityReminder (Wear OS)

## Language & Framework Conventions
- Kotlin only. Use idiomatic Kotlin (data classes, sealed classes, extension functions)
  over Java-style patterns.
- UI built with Jetpack Compose for Wear OS (not legacy XML layouts/Views).
- Use Wear OS-specific Compose components where available: `Stepper`, `Picker`,
  `SwipeDismissableNavHost`, `ScalingLazyColumn` — not standard Compose Material 3
  components designed for phone screens.

## Concurrency
- Use Kotlin Coroutines for the periodic inactivity-check loop inside the foreground
  service (e.g. a `CoroutineScope` tied to the service lifecycle with a `while` loop +
  `delay()`), not raw Handlers/Timers.
- Cancel coroutines properly in `onDestroy()` to avoid leaks.

## State Management
- All user-configurable state (interval, monitoring enabled) lives in
  `SettingsRepository` via DataStore, exposed as `Flow`.
- UI observes state via `collectAsState()` — no manual polling.
- Avoid duplicating state in ViewModel and Service separately; both should read from
  the same DataStore source.

## Naming Conventions
- Classes: PascalCase (`MotionDetector`, `InactivityForegroundService`)
- Functions: camelCase, verb-first (`startMonitoring()`, `resetInactivityTimer()`)
- Constants: UPPER_SNAKE_CASE in a companion object (`DEFAULT_INTERVAL_MINUTES = 30`)
- Flow properties: nouns, no "get" prefix (`inactivityIntervalMinutes`, not
  `getInactivityIntervalMinutes`)

## Notifications
- Always use `NotificationCompat` for backward compatibility, not raw `Notification.Builder`.
- Two distinct channel IDs: one for the ongoing/silent monitoring notification, one for
  the actual reminder alert. Never reuse a single channel for both purposes.

## Error Handling
- Sensor unavailability (e.g. accelerometer missing — shouldn't happen on this device,
  but check defensively) should fail gracefully with a user-facing message, not a crash.
- Foreground service start failures (e.g. missing permission) should be caught and
  surfaced to the UI, not silently swallowed.

## Testing Approach
- Prefer testing sensor/threshold logic in isolation via Logcat prints before wiring
  into the full service + UI flow.
- Use the Wear OS emulator's Extended Controls → Virtual sensors to simulate
  accelerometer input rather than waiting in real time during development.

## What NOT to suggest
- Do not suggest SharedPreferences — use DataStore.
- Do not suggest plain background Service without foreground promotion — it will be
  killed by the OS.
- Do not suggest polling HealthKit/Health Services hourly stand data as a substitute
  for real-time accelerometer monitoring — granularity is too coarse for this use case.
- Do not suggest XML-based Views for new UI — Compose for Wear OS only.
