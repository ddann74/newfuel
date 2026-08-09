# PRD: Fuel Alert (route-aware, full-screen fuel-price notifications)

Status: DRAFT — not yet approved for build.
Lives inside `ddann74/newfuel`, under `mobile/` — a native Android
companion to the existing Streamlit web app at the repo root, sharing
the same backend proxy contract (see §6).

## 1. Problem

The `newfuel` web app finds the best-value fuel station along a route,
but only when you open it and run a search. This app does the same job
passively: while you're driving, it watches for a genuinely good deal
nearby (or coming up on your route) and interrupts you with something
impossible to miss — not a notification you might swipe away without
reading.

## 2. Goals

- Detect that the phone is moving (driving), not just sitting still.
- While driving, periodically check fuel prices near the current
  position — or, if a destination is set, along the remaining route.
- When a station clears the user's value bar, show a **full-screen
  alert** (lock-screen-visible, alarm-style) — not a quiet notification
  shade entry.
- Let the user act immediately: Navigate / Snooze / Dismiss.
- Don't drain the battery or spam alerts.

## 3. Non-goals (out of scope for this PRD)

- iOS. Android only, matching every other app in this account's repos.
- Reading another app's active navigation session (e.g. pulling the
  destination out of Google Maps) — no reliable, sanctioned API for
  this. Destination is entered in-app, same as the web app.
- Multi-state fuel data. NSW FuelCheck only, same as the web app.
- Actually provisioning/hosting the backend proxy (§6) — that's an
  explicit open dependency, not something this build produces.

## 4. User stories

1. As a driver, I start a trip in the app before I set off, and don't
   have to think about it again — no manual re-checking.
2. As a driver on a plain drive (no destination set), I get alerted if
   a genuinely cheap station is near my current position.
3. As a driver with a destination set, I only get alerted about
   stations actually ahead of me on the route, with enough lead
   distance to react — not ones I've already passed, and not ones
   miles off the corridor.
4. As a driver, when I get an alert, it's unmistakable — full-screen,
   even over the lock screen — not something I could plausibly miss
   glancing at the road.
5. As a driver, I can snooze or dismiss an alert without it repeating
   for the same station.
6. As a user, I control the fuel type and the value bar (target price,
   or % below corridor/area average) so I only get alerted about deals
   that are actually worth the detour.

## 5. Functional requirements

### 5.1 Movement / trip detection
- `ActivityRecognitionClient` transitions gate everything else:
  GPS polling and price checks only run while `IN_VEHICLE` is active.
- A trip starts when driving is detected (or the user manually starts
  one) and ends after a period of non-driving (still/walking) beyond a
  short grace window, to avoid flapping at traffic lights.

### 5.2 Location tracking
- `FusedLocationProviderClient`, balanced-power priority, active only
  during a trip (see 5.1). No location polling while stationary.

### 5.3 Search modes
- **Near-me mode** (default, no destination set): checks stations
  within a configurable radius of current position.
- **Route-aware mode** (destination set): fetches the route polyline
  once (TomTom, via the backend proxy — §6), then as position updates
  arrive, filters candidate stations to ones:
  - within a configurable corridor width of the route, **and**
  - **ahead** of the current position along the route (not behind —
    reuse/port the `distToPolyline`/`samplePolyline` corridor math
    already written and working in `static/app.js`; add a
    route-progress check using the point's position along the
    polyline, not just its distance from it, to derive ahead/behind),
  - within a configurable lead distance (e.g. next 5–10 km) so there's
    time to act before passing the turnoff.
- Re-run the price/corridor check periodically during a trip (interval
  TBD during build, tune against battery/API-call cost), not once.

**Resolved 2026-08-09 — Waze's role is navigation-only, never
calculation:** Waze has no official public API for routing/ETA (the
only sanctioned developer access, the Waze Transport SDK, requires a
business partnership Google/Waze doesn't offer to individual apps).
Everything claiming to be "the Waze API" for route/ETA data (e.g.
WazeRouteCalculator) scrapes Waze's internal, undocumented livemap
endpoints — can break or get rate-limited without notice, and risks
violating Waze's Terms of Service. TomTom (already the plan, §5.3, via
the backend proxy) stays the sole source of truth for route polyline,
ETA, and corridor/ahead-of-position matching. Waze's role stays exactly
what §5.5's Navigate button already described: a deep link
(`https://waze.com/ul?ll=<lat>,<lon>&navigate=yes`) handing off to the
Waze app for turn-by-turn — the same sanctioned, documented mechanism
the web app's `nav-link` already uses — never a data source this app's
own calculations depend on.

### 5.4 Value threshold / alert trigger
- User sets one of: a manual target price, or "X% below corridor/area
  average."
- Alert fires only when a station clears the bar — not on every
  station found.
- Debounce: never re-alert for the same station within a trip; an
  alert for a station expires once the user has passed it (route-aware
  mode) or once it falls outside the radius (near-me mode).

### 5.5 Full-screen alert
- `NotificationChannel` at `IMPORTANCE_HIGH`, alarm-like category, with
  `setFullScreenIntent()` pointing at a dedicated `FullScreenAlertActivity`.
- Activity shows over the lock screen (`setShowWhenLocked` +
  `setTurnScreenOn` or the modern equivalent), displays station name,
  price, distance/detour, and **Navigate** (deep-links to Waze, same
  pattern as the web app) / **Snooze** / **Dismiss** actions.
- **Must research and confirm during build, not assume:** Android 14+
  requires `USE_FULL_SCREEN_INTENT` to be manually granted by the user
  via `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` — it is not
  auto-granted to non-dialer/alarm apps. The build must include a
  first-run flow that sends the user to that settings screen and
  explains why, mirroring Spot Block's accessibility-settings prompt.
  Confirm exact current behavior (it has changed across Android
  versions) against real developer.android.com docs before
  implementing — the same "verify, don't assume" standard used
  throughout this account's other Android projects.

### 5.6 Settings
- Fuel type (E10/U91/U95/U98/P98/Diesel), search radius (near-me) /
  corridor width + lead distance (route-aware), value threshold mode
  and value, trip auto-start behavior.

## 6. Backend dependency (open — not built by this PRD)

The app never embeds the NSW FuelCheck or TomTom API keys directly —
same reasoning as the fix already applied to the web app's
`static/app.js`: a key shipped in a client (browser JS or, worse, a
redistributable APK) is not private. Both the web app and this app
call the **same proxy contract**, already implemented once at
`proxy_server.py` in this repo's root:

- `GET /proxy/tomtom-route?olat&olon&dlat&dlon&routeType` → route JSON
- `POST /proxy/nsw-stations` `{fuelType, lat, lon, radius}` → station
  price JSON

**Open dependency, explicitly not resolved by this PRD:** where this
proxy actually runs reachable by a phone on a cellular/wifi network
(the web app's version only needs to be reachable by a browser on the
same machine or network as the Streamlit server; a phone needs it
reachable from anywhere). Hosting has not been decided. Until it is:
- The Android build target's base URL is a single configurable value
  (build config field or in-app setting), not hardcoded.
- Development/testing runs against a local mock server (same response
  shapes as the two endpoints above) so the rest of the app is fully
  buildable and testable without a live backend.

## 7. Non-functional requirements

- **Battery:** no GPS/network activity outside an active,
  driving-detected trip.
- **Privacy:** location data is used locally for route-matching and
  sent only to the proxy for the specific lookup needed — never logged
  or transmitted beyond that, matching this account's other apps'
  stated design philosophy (see Spot Block's README as the reference
  pattern).
- **Permissions:** `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`,
  `ACTIVITY_RECOGNITION`, `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`,
  `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION`. Each gated
  permission gets an explainer shown before the system prompt, not a
  bare OS dialog with no context.

## 8. Architecture / components

| Component | Responsibility |
|---|---|
| `TripMonitorService` | Foreground service; owns ActivityRecognition + Location updates during a trip |
| `RouteMatcher` | Route polyline fetch + corridor/ahead-of-position filtering (ports the web app's proven JS math) |
| `PriceFetcher` | Talks to the proxy contract (§6); swappable mock backend for dev/test |
| `AlertEngine` | Threshold evaluation + debounce/expiry |
| `FullScreenAlertActivity` | Lock-screen-visible alert UI |
| Settings screen | Fuel type, radius/corridor, threshold, trip behavior |

## 9. Verification standard for the build

Same standard already applied to this account's other Android projects
(Spot Block): every file compile-checked for real wherever the
sandbox's network restrictions allow (a real Android API stub jar +
standalone Kotlin compiler, since `dl.google.com` is typically
unreachable in-sandbox), any unverifiable file (e.g. one depending on
a Google-Maven-only artifact) explicitly disclosed as such rather than
silently assumed correct, and no claim of "done" without saying exactly
how it was checked.

## 10. Milestones (build order)

1. Project scaffold (Gradle, manifest, permissions, mock backend server for dev)
2. `PriceFetcher` + mock backend, `RouteMatcher` ported from `static/app.js` with unit tests against known coordinates
3. `TripMonitorService` (ActivityRecognition + Location, foreground service lifecycle)
4. `AlertEngine` (threshold + debounce logic, unit tested)
5. `FullScreenAlertActivity` + notification channel + Android 14+ permission-grant flow (researched, not assumed)
6. Settings screen
7. End-to-end wiring + manual test plan for a real device (this sandbox cannot run/emulate a real GPS trip)
