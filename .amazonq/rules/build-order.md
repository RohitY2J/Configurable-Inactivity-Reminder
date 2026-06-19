# Build Order & Testing — InactivityReminder (Wear OS)

## Recommended Implementation Sequence

Build and test each piece in isolation via Logcat before wiring into the next layer.
This catches sensor/threshold bugs early without UI noise.

1. **SettingsRepository** (`data/`) — DataStore wrapper for interval + enabled flag.
   Small, self-contained, easy to verify independently.
2. **MotionDetector** (`sensor/`) — SensorManager wrapper. Test with Logcat prints
   showing accelerometer deltas and `lastMovementTimestamp` updates before connecting
   anything else.
3. **InactivityForegroundService** (`service/`) — Wire MotionDetector + SettingsRepository
   together. Verify the coroutine loop and threshold comparison logic via Logcat.
4. **ReminderNotifier** (`notification/`) — Wire into the service. Confirm both
   notification channels behave correctly (silent ongoing vs. alerting reminder).
5. **HomeScreen UI** (`presentation/`) — Connect to service start/stop and status display.
6. **SettingsScreen UI** (`presentation/`) — Connect to SettingsRepository for interval
   selection (Stepper/Picker).
7. **MainActivity permissions flow** — Runtime request for `BODY_SENSORS` and
   `POST_NOTIFICATIONS`, then wire everything together.

## Testing Checklist

- [ ] Emulator: service starts, persistent notification appears in shade
- [ ] Emulator: use Extended Controls → Virtual sensors to simulate accelerometer
      values and trigger inactivity logic without real-time waiting
- [ ] Physical watch: stay still, confirm reminder fires at the configured interval
- [ ] Physical watch: confirm reminder does NOT fire when off-wrist
      (validates `TYPE_OFFBODY_DETECTION` logic)
- [ ] Battery check: monitor drain over a few hours of continuous service running
- [ ] Reboot test: confirm expected behavior on watch restart (service should not
      auto-resume unless a `BOOT_COMPLETED` receiver is explicitly added — optional)

## Editor Workflow

- VS Code + Amazon Q: pure Kotlin logic — `MotionDetector.kt`, `SettingsRepository.kt`,
  `InactivityForegroundService.kt`. No Compose UI involved in these files.
- Android Studio: anything in `presentation/` (Compose UI), emulator runs, physical
  device deployment, Logcat debugging, Gradle sync issues.

## Known Gotchas
- Foreground service type must be `health`, not default — wrong type crashes on
  Wear OS 6 / Android 14+.
- `BODY_SENSORS` permission is runtime-requested — don't skip the request flow.
- Confirmed accessible sensors on Galaxy Watch 5 Pro for third-party apps:
  accelerometer, gyroscope, off-body detection. Some Samsung "private" sensors are
  not accessible — don't assume `getSensorList()` results are all usable.
