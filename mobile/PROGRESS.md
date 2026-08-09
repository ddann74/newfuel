# Build progress (Fuel Alert)

Read this file first, then `PRD.md`, at the start of every iteration.
This file is the only memory the build loop has between iterations —
each iteration runs in a fresh context with no memory of prior ones.
Update it before finishing every iteration, even if the item isn't
fully done — partial progress and blockers belong here too.

Rules for every iteration:
- Pick the **first unchecked item** below, top to bottom. Don't skip
  ahead even if a later item looks easier.
- Implement it for real — no stubs, no "TODO: implement later" left
  in committed code.
- Compile-check what you write against a real Android API surface,
  same technique used elsewhere in this account's Android projects
  (real `android-all` stub jar + standalone Kotlin compiler, since
  `dl.google.com` is normally unreachable in-sandbox). If something
  genuinely can't be verified that way (e.g. it depends on a
  Google-Maven-only artifact), say so explicitly in the commit message
  and in this file's Notes section — never silently assume it's right.
- Commit and push to the `mobile-fuel-alert` branch (not `main` —
  `main` also serves the live web app; this branch merges via PR once
  the milestones below are done and reviewed).
- Check the box, add a one-line dated note on what was actually built
  and how it was verified, then stop the iteration.
- If you hit a genuine open decision the PRD doesn't resolve, don't
  guess — write it under Notes/Blockers below and stop; a human will
  resolve it before the next iteration.

## Milestones

- [ ] 1. Project scaffold — Gradle, manifest, permissions declared,
      mock backend server for dev (matches `proxy_server.py`'s
      `/proxy/tomtom-route` and `/proxy/nsw-stations` response shapes)
- [ ] 2. `PriceFetcher` + mock backend wiring; `RouteMatcher` ported
      from `static/app.js`'s corridor math, with unit tests against
      known real coordinates
- [ ] 3. `TripMonitorService` — ActivityRecognition + Location,
      foreground service lifecycle
- [ ] 4. `AlertEngine` — threshold + debounce logic, unit tested
- [ ] 5. `FullScreenAlertActivity` + notification channel + Android
      14+ `USE_FULL_SCREEN_INTENT` grant flow (research actual current
      behavior against developer.android.com before implementing —
      do not assume it matches an earlier Android version's rules)
- [ ] 6. Settings screen
- [ ] 7. End-to-end wiring + a written manual test plan for a real
      device (this sandbox cannot run/emulate a real GPS trip, so this
      milestone's own verification is necessarily a plan, not a run —
      say so plainly when marking it done)

## Notes / blockers

(none yet)
