# MunozGPS — personal commercial-truck navigation app

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
    routing/RouteRepository.kt   # truck-legal route calculation (TomTom Routing REST API)
    routing/RouteGeometry.kt     # cumulative-distance math for the route polyline
    search/SearchRepository.kt   # destination search (TomTom Search REST API)
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
You can also trigger a build on demand from the **Actions** tab → "Build MunozGPS APK" →
**Run workflow**, without pushing a new commit.

## Why REST calls instead of TomTom's native Routing/Search SDK

The map itself (`TomTomMap`, markers, the drawn route polyline, live traffic layers, camera
control) uses TomTom's native "Maps and Navigation SDK" (`com.tomtom.sdk.maps:map-display-standard-android-complete`),
verified directly against TomTom's own Dokka API reference during development. Truck routing
and destination search, however, call TomTom's public REST APIs directly
(`api.tomtom.com/routing/1/calculateRoute`, `api.tomtom.com/search/2/search`) instead of the
native Routing/Search SDK modules. Two reasons:

1. The native SDK's typed routing model is deep (a sealed `Instruction` hierarchy with ~18
   maneuver-specific subtypes and no plain `.text`, physical-quantity wrapper types for
   distance/duration, `Vehicle.Truck`/`MarkerOptions` marked `@RestrictToExtendedFlavor` in the
   reference docs) — precisely mapping it by hand led to multiple rounds of CI failures.
2. The REST endpoints are TomTom's long-stable, thoroughly documented v1/v2 API — the same one
   the original HTML prototype this app is based on already used successfully — so it's the
   more reliable path to guaranteed-working truck-legal routing on a personal/free API key.

If you'd rather use the native `RoutePlanner`/`Search` objects (e.g. for offline routing or
richer guidance events), see `TomTomSdk.createRoutePlanner()` / `TomTomSdk.createSearch()` in
`com.tomtom.sdk.init` — `RouteRepository`/`SearchRepository` are the two files to swap.

## Known caveats / next steps for whoever picks this up

- **Voice guidance is DIY, not TomTom's paid Navigation SDK.** It's built from the REST routing
  response's turn-by-turn instruction list (`guidance.instructions[].message`) plus Android's
  on-device `TextToSpeech`, so it works with a standard/free TomTom API key. TomTom also ships a
  full `GuidanceUpdatedListener`-based navigation stack if you later want lane guidance,
  speed-limit warnings, etc. — a bigger integration intentionally skipped for a personal-use MVP.
- **No background/foreground-service navigation yet.** Voice guidance runs while the app is in
  the foreground with the screen kept on; it doesn't currently continue if you switch apps.
- **Launcher icon** is a simple placeholder vector, not real artwork.
- **CI-verified but not device-tested.** The GitHub Actions build compiles this for real on
  every push (see the badge-worthy fact that it's iterated through several real Gradle/TomTom
  SDK errors already), which catches compile-time mistakes, but nobody has installed the
  resulting APK on an actual phone yet — runtime behavior (permissions flow, voice timing, map
  rendering) still needs a real test pass.
