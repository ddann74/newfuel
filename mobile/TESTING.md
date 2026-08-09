# Manual test plan (real device required)

Nothing in this file has been run - this sandbox cannot build a real
APK (Play Services and androidx are Google-Maven-only, unreachable
here) or simulate a real GPS trip. Every step below is a real,
concrete thing to actually do on a real device before trusting this
app works, not a description of what should theoretically happen.

## 0. Before anything else: does it actually build?

This is the single biggest unknown. Every file that depends on
`androidx.*` or `com.google.android.gms.*` was written from careful,
research-backed knowledge of the real APIs and compile-checked wherever
this sandbox's tooling allowed (see `PROGRESS.md`'s per-milestone
notes for exactly what was and wasn't verified) - but **none of it has
been compiled by a real Gradle/AGP build.**

1. Open `mobile/` in Android Studio, let Gradle sync.
2. `./gradlew assembleDebug`.
3. If it fails: the error will point at a specific file/line. Cross-
   reference against `PROGRESS.md` - if it's in a file already flagged
   "UNVERIFIED BEYOND CAREFUL REVIEW" (`TripMonitorService.kt`,
   `TripActivityTransitionReceiver.kt`, `MainActivity.kt`,
   `FullScreenAlertActivity.kt`, `FullScreenAlertNotifier.kt`), that's
   the expected place for a real mistake to surface - not a sign
   anything else is suspect. Spot Block (`ddann74/spot_block`) hit
   exactly one such error on its first real build (a property-shadowing
   bug in `AdMusicPlaybackService.kt`) - expect something similar here,
   not a clean first build.

## 1. Local test backend (no real API keys needed for this)

The debug build already points `BuildConfig.BACKEND_BASE_URL` at
`http://10.0.2.2:8765` (the Android emulator's alias for your machine's
localhost).

1. `python3 mobile/tools/mock_backend.py` on your dev machine.
2. Run the app in an emulator (not a physical device - `10.0.2.2` only
   resolves correctly from an emulator).
3. This gets you canned-but-internally-consistent fake stations/routes
   - enough to exercise every code path, but not real NSW FuelCheck
   prices. Real backend hosting is still an open PRD dependency (see
   PRD.md ss6) - testing against real data needs that resolved first,
   and needs a physical device (or reconfiguring `BACKEND_BASE_URL`
   for an emulator pointed at a real host) since a phone can't reach
   your dev machine's `10.0.2.2`.

## 2. Settings screen + permission flow

1. Open the app. Confirm every field (fuel type, radius, corridor
   width, lead distance, threshold mode/value, trip auto-start) shows
   the PRD-documented defaults on first launch.
2. Change each field, kill and reopen the app, confirm the values
   persisted (this exercises `SettingsRepository`, which - unlike most
   of this app - was fully run-tested, not just compile-checked; a
   real-device mismatch here would be surprising and worth reporting
   precisely).
3. Tap **Grant Location** - confirm the explainer text was visible
   *before* you tapped it, and the system dialog requests fine
   location + activity recognition + notifications together.
4. Tap **Grant Background Location** *before* granting Location above
   - confirm it refuses with the explanatory toast, not a crash or a
   silent no-op.
5. Grant Location, then tap **Grant Background Location** - confirm
   Android shows its own separate background-location prompt (this is
   an OS behavior, not something the app controls - if it doesn't
   appear separately, something is wrong with how the permission was
   requested).
6. Tap **Open Full-Screen Alert Settings**. On Android 14+: confirm it
   opens the real system settings screen for this permission (not a
   crash, not a no-op). On Android 13 and below: confirm `isGranted()`
   already reports true without needing this button at all (the
   researched pre-14 auto-grant behavior - see PRD.md ss5.5's
   "Resolved" note).
7. Confirm the status text at the bottom accurately reflects each
   permission's real state after granting/revoking it via system
   Settings directly (not just via this screen's own buttons).

## 3. Trip start/stop and the foreground service

1. Leave Destination blank, tap **Start Trip**. Confirm the "Fuel Alert
   is watching your drive" notification appears (this is
   `TripMonitorService`'s foreground notification - if this doesn't
   appear, the service likely isn't starting correctly).
2. Tap **Stop Trip**. Confirm the notification disappears.
3. Enter a real destination (e.g. a suburb near you), tap **Start
   Trip**. Confirm the status text shows "Finding..." then either
   "Monitoring - route-aware to ..." or a specific not-found message -
   never a silent failure.

## 4. Activity recognition (needs actual driving, or a route simulation tool)

This is the hardest part to test without actually driving somewhere.
Android's `ActivityRecognitionClient` needs real (or simulated) motion
to fire `IN_VEHICLE` transitions - starting the app stationary will not
trigger location updates to start, by design (PRD.md ss5.1 - no GPS
activity outside a detected drive).

1. With a trip started, actually drive somewhere (or use Android
   Studio's emulator location route-playback feature to simulate
   movement along a road).
2. Confirm location updates only begin once driving is actually
   detected - check `adb logcat` or add temporary logging if the
   foreground notification alone doesn't make this obvious.
3. Stop at a red light for under 5 minutes, confirm the trip does NOT
   end (the grace window - `TripStateMachineTest.kt` verified this
   logic in isolation, but the real `ActivityRecognitionClient`
   transitions feeding it have never been exercised for real).
4. Stop moving for a genuinely long stretch (parking, arriving), confirm
   the trip DOES end and the foreground notification/location updates
   stop.

## 5. The actual alert

1. With the mock backend running (its 5 canned stations are always
   "nearby" regardless of your real position) and a trip active,
   confirm a full-screen alert eventually appears once a station clears
   your configured threshold.
2. **Lock the phone before the alert should fire.** Confirm it shows
   over the lock screen (this is the entire point of PRD.md ss5.5 - if
   it doesn't, `USE_FULL_SCREEN_INTENT` likely isn't granted, or
   something about the notification/channel setup is wrong).
3. If you deliberately leave the full-screen-intent permission
   ungranted (see ss2.6 above), confirm the alert still shows as a
   heads-up notification (the researched, documented fallback - PRD.md
   ss5.5) rather than nothing at all.
4. Tap **Navigate** - confirm Waze opens (or the Play Store if Waze
   isn't installed) to the station's location.
5. Tap **Snooze** on one alert, confirm the SAME station can alert
   again later (per `AlertEngine`'s debounce/expiry design -
   `AlertEngineTest.kt` verified the logic; this checks the real UI
   path reaches it correctly).
6. Tap **Dismiss** on another, confirm the notification actually
   clears from the shade.

## 6. Known gaps this plan does NOT cover

- Real NSW FuelCheck/TomTom data (blocked on backend hosting, PRD.md ss6).
- Battery impact over a real multi-hour drive - the 30-second location
  interval (`TripMonitorService.LOCATION_INTERVAL_MILLIS`) is a
  starting guess, not tuned against real battery data.
- Whether the sound/vibration on the fuel-alert notification channel
  actually feels "alarm-like" rather than routine - subjective, worth
  a real listen.
- Multi-day/multi-trip behavior (does the app behave correctly if
  killed by the OS mid-trip and restarted, etc.) - `START_STICKY` is
  used, but its real-world restart behavior across different OEM
  battery-optimization behaviors is notoriously inconsistent and
  untestable from this sandbox.
