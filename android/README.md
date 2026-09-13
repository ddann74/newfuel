# Fuel Optimizer - Android

A single-Activity WebView wrapper around the same web app the Streamlit
path (`../app.py`) serves - `MainActivity` loads the bundled
`app/src/main/assets/index.html` + `style.css` + `app.js`, which are kept
in sync with the canonical copies under `../static/` by the `:app`
module's `syncWebAssets` Gradle task (runs automatically before every
build - see `app/build.gradle`).

## Opening this project

Open the `android/` folder itself in Android Studio (not the repo root -
`android/` is the actual Gradle project root, with the web app's
Python/Streamlit code living alongside it in the parent folder).

There's no committed `gradlew`/`gradlew.bat`/wrapper jar in this repo -
Android Studio generates these automatically on first sync using its own
bundled Gradle, so this isn't a broken setup, just a normal one-time step
you'll see happen the first time you open the project.

**This project has not been built or run in the environment it was
written in** - that environment has no Android SDK installed and its
network policy blocks `dl.google.com` (Google's Maven repository, where
the Android Gradle Plugin and AndroidX libraries are hosted), so a real
Gradle sync/build could not be attempted there. Everything here was
hand-written to match standard, well-documented Android/Gradle
conventions and checked as far as that environment allowed (Gradle DSL
parses; every XML file is well-formed; Java syntax and brace/paren
balance verified) - but the FIRST real build/run needs to happen in
Android Studio on a machine with normal internet access and the Android
SDK installed (Android Studio installs the SDK for you on first launch
if it isn't there already).

## Two things worth knowing about how this works

**Why the app loads from `https://appassets.androidplatform.net/...`
instead of `file:///android_asset/...`:** the web app's own `app.js`
already has an error message anticipating a CORS failure
("CORS restriction — run via local server or Streamlit proxy") for the
NSW FuelCheck login call when not loaded from a real `http(s)` origin. A
raw `file://` page sends `Origin: null` on every `fetch()`, which most
permissive CORS setups don't reliably treat the same as a real origin.
`MainActivity` uses `androidx.webkit`'s `WebViewAssetLoader` - Google's
own documented fix for exactly this - to serve the bundled assets over a
real `https://` origin instead. **This has not been verified against the
live NSW FuelCheck API** (no device/network access to that specific host
in the environment this was written in) - if fetches still fail with a
CORS error in real use, the API itself would need a real server-side
proxy in front of it; no client-side trick fixes a server that strictly
allowlists specific known origins rather than allowing all of them.

**Why `target="_blank"` links (the Waze navigation links) need
`onCreateWindow`:** a bare WebView silently drops `window.open()`/
`target="_blank"` clicks with no visible error. `MainActivity`
implements the standard (if slightly unusual-looking) workaround: a
temporary, never-displayed `WebView` is handed the navigation attempt so
its own `WebViewClient` can read the real target URL, which is then
launched as a normal Android `Intent` (handing off to the real Waze app
if it's installed, or a browser otherwise) instead of trying to open a
second WebView window inside the app.

## Known gaps

- The app icon (`ic_launcher_background.xml`/`ic_launcher_foreground.xml`)
  is a plain placeholder (a solid color + a simple drop shape), not a
  real design - swap it for something better whenever there's one to
  drop in.
- `minSdk` is 26 (Android 8.0+) specifically so only the modern adaptive
  icon format is needed, with no separate legacy icon set for the
  narrower API 24-25 slice.
