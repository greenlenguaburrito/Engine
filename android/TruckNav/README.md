# TruckNav — personal commercial-truck navigation app

A native Android app for personal use: truck-legal routing (weight/height/length/hazmat
restrictions honored, not just car routes), live traffic, and spoken turn-by-turn voice
guidance. It's the native-app version of the single-file HTML prototype this repo started
from — same TomTom-powered routing/traffic idea, but a real installable app with on-device
text-to-speech guidance instead of a browser tab.

## ⚠️ The API key in this repo is public — that was a deliberate tradeoff

At the repo owner's explicit request, `app/build.gradle.kts` bakes in a real TomTom API key
as its default value, so the app and the CI build below work with zero setup. **Because this
repo is public, that key is effectively public too** — treat it as already leaked, the same
way the key hardcoded in the original HTML prototype should be. If usage/billing on that key
ever looks abused, rotate it at [developer.tomtom.com](https://developer.tomtom.com) and either
swap the new one directly into `build.gradle.kts`, or override it locally without touching
git history: copy `local.properties.template` to `local.properties` and set `tomtom.api.key=`
there (gitignored, takes priority over the hardcoded default) — see "Setup" below.

## What's implemented

- **Truck-legal routing** — sends your vehicle's weight, height, length, and hazmat status to
  TomTom's truck routing engine so the route avoids low bridges, weight-restricted roads, and
  (when hazmat is on) hazmat-restricted tunnels/zones. Truck profile is editable from the
  "Dimensions" button and persists between launches.
- **Live traffic** — flow coloring + incident icons toggle on the map, and the ETA card shows
  a traffic-delay callout when the live route is running behind.
- **Voice guidance** — turn-by-turn instructions from the routing engine are spoken aloud via
  Android's built-in text-to-speech as you approach each maneuver (1 mile / half mile / ~1,000 ft
  / imminent), independent of any paid navigation add-on.
- **Destination search** — free-text address/POI search biased to your current location.

## Project layout

```
android/TruckNav/
  app/src/main/java/com/trucknav/pro/
    MainActivity.kt              # screen + state machine (search → route → nav)
    model/                       # TruckProfile, LatLng, TruckRoute, RouteInstruction
    data/TruckProfileStore.kt    # persists truck dimensions/hazmat via SharedPreferences
    routing/RouteRepository.kt   # truck-legal route calculation (TomTom Routing SDK)
    routing/RouteGeometry.kt     # cumulative-distance math for the route polyline
    search/SearchRepository.kt   # destination search (TomTom Search SDK)
    traffic/TrafficController.kt # live traffic flow/incident overlay toggle
    location/LocationTracker.kt  # FusedLocationProviderClient wrapper
    voice/RouteProgressTracker.kt# GPS → "how far to the next turn" → announcement triggers
    voice/VoiceGuidanceEngine.kt # Android TextToSpeech wrapper
    ui/TruckProfileDialogFragment.kt
  app/src/main/res/              # layouts, icons, strings mirroring the HTML prototype's UI
```

## Setup

No API key setup is required — a working key is already baked into `app/build.gradle.kts`.

1. **Open `android/TruckNav/` in Android Studio** (Koala/2024.x or newer) and let it sync.
   The Gradle wrapper is committed, so no local Gradle install is required.
2. **Run** on a device or emulator with Google Play services (required for location updates)
   and internet access.

Want to use your own key instead of the committed default? Copy `local.properties.template`
to `local.properties` and set `tomtom.api.key=<your key>` (gitignored, overrides the default) —
or set a `TOMTOM_API_KEY` environment variable, which `app/build.gradle.kts` also checks first.

## Building an installable APK without a computer

Every push to this branch triggers `.github/workflows/android-build.yml`, which builds a debug
APK on GitHub's servers and publishes it to this repo's **Releases** page
(github.com/greenlenguaburrito/Engine/releases, tag `truck-nav-debug`) as a direct-download
`.apk` file. From a phone: open that Releases page in a browser, download the APK, then install
it (Android will prompt you to allow installs from your browser/file manager the first time).
You can also trigger a build on demand from the **Actions** tab → "Build TruckNav APK" →
**Run workflow**, without pushing a new commit.

## Known caveats / next steps for whoever picks this up

- **Not yet compiled.** This was authored without an Android SDK/emulator available in the
  build environment, so it hasn't been run through Gradle. TomTom's Android SDK moves its
  package layout and artifact versions between releases fairly often; if Gradle sync flags an
  unresolved import or a changed version number, use Android Studio's import quick-fix / check
  the current numbers at the docs below and adjust — the overall architecture (repositories,
  the progress tracker, the UI state machine) doesn't depend on those specifics.
  - Maps SDK setup: https://docs.tomtom.com/maps/android/getting-started/project-setup
  - Navigation SDK setup: https://docs.tomtom.com/navigation/android/getting-started/project-setup
  - Routing guide: https://docs.tomtom.com/navigation/android/guides/routing/planning-a-route
- **Voice guidance is DIY, not TomTom's paid Navigation SDK.** It's built from the routing
  engine's turn-by-turn instruction list plus Android's on-device `TextToSpeech`, so it works
  with a standard/free TomTom API key. TomTom also ships a full `GuidanceUpdatedListener`
  based navigation stack if you later want lane guidance, speed-limit warnings, etc. — that's
  a bigger integration this app intentionally skipped for a personal-use MVP.
  See https://docs.tomtom.com/navigation/android/guides/navigation/turn-by-turn-navigation
- **No background/foreground-service navigation yet.** Voice guidance runs while the app is in
  the foreground with the screen kept on; it doesn't currently continue if you switch apps.
- **Launcher icon** is a simple placeholder vector, not real artwork.
