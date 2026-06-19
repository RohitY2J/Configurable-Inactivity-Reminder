# InActivityReminder — Wear OS

A Wear OS app for Samsung Galaxy Watch 5 Pro that detects physical inactivity via the accelerometer and fires a user-configurable reminder notification, unlike the built-in Stand reminder which is fixed at 50 minutes and not adjustable.

---

## A. Project Definition

InActivityReminder monitors the user's physical activity through the watch's accelerometer sensor. When the user remains still for a configured duration (default 30 minutes, range 1–60 minutes), the app fires a vibrating notification reminding them to move. The app runs as a foreground service to maintain continuous sensor access even when the screen is off.

**Why this app exists:**
- Samsung's built-in Stand reminder is fixed at 50 minutes with no user control.
- This app provides a fully adjustable inactivity threshold.
- Real-time accelerometer monitoring provides more accurate inactivity detection than hourly health data polling.

---

## B. Architecture

The app follows a strict layered architecture with clear separation of concerns:

```
┌─────────────────────────────────────────────────────┐
│  presentation/   → Compose UI (MainActivity)         │
│                    Start/Stop toggle + Interval       │
│                    Stepper. No sensor/service logic.  │
├─────────────────────────────────────────────────────┤
│  service/        → InactivityForegroundService       │
│                    Orchestrates sensor + notifications│
│                    Coroutine check loop every 15s     │
├─────────────────────────────────────────────────────┤
│  sensor/         → MotionDetector                    │
│                    Wraps SensorManager (accelerometer │
│                    + off-body). No UI or notification │
│                    code.                              │
├─────────────────────────────────────────────────────┤
│  notification/   → ReminderNotifier                  │
│                    Builds and fires notifications.    │
│                    Two channels: silent + alerting.   │
├─────────────────────────────────────────────────────┤
│  data/           → SettingsRepository                │
│                    DataStore<Preferences> wrapper.    │
│                    Single source of truth for prefs.  │
└─────────────────────────────────────────────────────┘
```

**Key rules:**
- `presentation/` never talks directly to `SensorManager` or `NotificationManager`.
- UI only reads/writes through `SettingsRepository` and starts/stops the service.
- Both UI and service observe the same DataStore Flows — no duplicated state.

---

## C. Targeted Version & Stats

| Property | Value |
|----------|-------|
| Target SDK | 36 (Android 16) |
| Min SDK | 30 (Wear OS 3.0) |
| Compile SDK | 36 |
| Language | Kotlin |
| UI Framework | Jetpack Compose for Wear OS (Material3) |
| Target Device | Samsung Galaxy Watch 5 Pro |
| Standalone | Yes (no companion phone app required) |
| Foreground Service Type | `health` |
| Gradle | 9.4.1 |
| AGP | 9.2.1 |
| Kotlin | 2.2.10 |

**Permissions:**
| Permission | Purpose |
|-----------|---------|
| `BODY_SENSORS` | Access accelerometer data |
| `ACTIVITY_RECOGNITION` | Required for foreground service type health (API 34+) |
| `HIGH_SAMPLING_RATE_SENSORS` | Required for foreground service type health (API 34+) |
| `FOREGROUND_SERVICE` | Run foreground service |
| `FOREGROUND_SERVICE_HEALTH` | Specify health type for the foreground service |
| `POST_NOTIFICATIONS` | Show reminder notifications (API 33+) |
| `WAKE_LOCK` | Keep CPU alive when screen is off |

---

## D. Workflow

### User Flow
1. User opens the app → Permissions are requested (BODY_SENSORS, ACTIVITY_RECOGNITION, POST_NOTIFICATIONS).
2. User taps **Start** → Foreground service starts, monitoring notification appears.
3. User adjusts interval with the **Stepper** (1–60 minutes).
4. User goes about their day — app monitors movement in the background.
5. If inactive for the configured duration → Vibrating "Time to move!" notification fires.
6. User can tap **Snooze 10 min** on the notification to delay the next reminder.
7. User taps **Stop** → Service stops, monitoring ends.

### Internal Flow
```
MainActivity (Start tap)
    → startForegroundService(InactivityForegroundService)
        → startForeground(type=health)
        → acquireWakeLock(PARTIAL)
        → motionDetector.start() [registers sensor listeners]
        → startCheckLoop() [coroutine, every 15s]:
            → read lastMovementTimestamp from MotionDetector
            → read interval from SettingsRepository (DataStore)
            → if (now - lastMovement >= threshold):
                → ReminderNotifier.fireReminderNotification()
                → motionDetector.resetTimer()
```

### Build & Deploy
1. Open project in Android Studio.
2. Gradle Sync.
3. Connect Wear OS emulator or physical watch via ADB.
4. Run `app` configuration.
5. Grant permissions when prompted.
6. Tap Start and test.

---

## E. Features

- **Configurable inactivity interval** — 1 to 60 minutes in 1-minute increments via Wear OS Stepper component.
- **Real-time accelerometer monitoring** — Detects actual physical movement, not step counts or hourly summaries.
- **Off-body detection** — Suppresses false alerts when the watch is removed (uses TYPE_LOW_LATENCY_OFFBODY_DETECT sensor).
- **Persistent foreground service** — Survives screen-off, Doze mode (with wake lock), and process reclamation.
- **Snooze action** — "Snooze 10 min" button on the reminder notification resets the timer.
- **Two notification channels** — Silent ongoing (service indicator) and high-priority alerting (reminder with vibration).
- **Reactive state management** — DataStore + Kotlin Flows ensure UI and service always reflect the same state.
- **Detailed logging** — State transitions (active ↔ inactive), sensor deltas, and check loop ticks logged for debugging.
- **Standalone app** — No companion phone app required.

---

## F. Problems & Solutions

### 1. SecurityException on startForeground (targetSdk 36)
**Problem:** Starting a foreground service with `foregroundServiceType="health"` on API 34+ requires additional permissions beyond just `FOREGROUND_SERVICE_HEALTH`.

**Error:** `Starting FGS with type health requires permissions: any of [ACTIVITY_RECOGNITION, HIGH_SAMPLING_RATE_SENSORS, ...]`

**Solution:** Added `ACTIVITY_RECOGNITION` and `HIGH_SAMPLING_RATE_SENSORS` to the manifest and included `ACTIVITY_RECOGNITION` in the runtime permission request flow.

---

### 2. Service dies when watch screen turns off (Samsung)
**Problem:** Samsung Galaxy Watch aggressively suspends CPU even for foreground services. The coroutine `delay()` freezes indefinitely when CPU sleeps.

**Solution:** Acquire a `PARTIAL_WAKE_LOCK` (indefinite, non-reference-counted) to keep the CPU alive. The wake lock is held for the lifetime of the service and released in `onDestroy()`.

**Additional step:** User must manually whitelist the app: **Settings → Apps → InActivityReminder → Battery → Unrestricted**.

---

### 3. Accelerometer too sensitive on physical watch
**Problem:** On a real wrist, micro-tremors (heartbeat, blood flow, tiny involuntary movements) produce accelerometer deltas of 1–4 m/s², constantly resetting the inactivity timer. The reminder never fires.

**Solution:** Raised the movement threshold from 1.5 to 5.0 m/s². Only deliberate motion (walking, arm raise) now registers as "active". Periodic sample logging (`Accel sample #N: delta=X.XX`) helps tune this value per device.

---

### 4. Off-body sensor incorrectly reporting on physical device
**Problem:** The off-body detection sensor (`TYPE_LOW_LATENCY_OFFBODY_DETECT`) may not be available or may report incorrect values on some Samsung watches, causing all inactivity checks to be skipped.

**Solution:** Removed the off-body guard from the main check loop. The check now runs regardless of on-body state, ensuring reminders always fire when the user is inactive.

---

### 5. No logs or notifications on physical device
**Problem:** After switching to `AlarmManager.setExactAndAllowWhileIdle()` for Doze-resistant scheduling, alarms silently failed because `SCHEDULE_EXACT_ALARM` requires explicit user grant on API 31+.

**Solution:** Reverted to the simpler coroutine loop + wake lock approach. The wake lock keeps the CPU alive, making `delay()` reliable without needing AlarmManager.

---

### 6. Emulator-only errors (non-issues)
**Problem:** `SensorService: Tried enabling a sensor without holding permission` and `ConnectException: failed to connect to /10.0.2.2` appear in Logcat.

**Solution:** These are emulator/GMS internal logs, not from the app. Safe to ignore. They don't appear on physical devices.

---

## License

MIT
