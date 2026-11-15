# CLAUDE.md

## Project

MyTeslamateApiMobile: personal Android app (Kotlin, Jetpack Compose) that reads TeslaMate data through a TeslaMate API instance (MyTeslaMate hosted `https://<sub>.api.myteslamate.com`, or self-hosted) and offers filters and statistics. UI strings and code comments are in French — keep it that way.

## Build

Same conventions as MyRadioPlayer: no Gradle wrapper (system `gradle` 9.9.1, JDK 17, compileSdk 37, targetSdk 34, AGP 9 with built-in Kotlin). CI = thin callers of `florianfish/Forge-Apk` reusable workflows (`build.yml` → debug APK + `debug` pre-release with fixed link, `release.yml` → signed release on `v*` tag, needs `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` secrets). No tests.

## Architecture

Single module `:app`, package `fr.florian.teslamate`. No networking/DI/navigation libraries: `HttpURLConnection` + `org.json`, Compose state, manual navigation.

- `TeslaMateApi.kt` — `Connection` (SharedPreferences `connexion`; Basic auth with user = URL subdomain by default, or Bearer token), blocking client (call on `Dispatchers.IO`), models, JSON parsing. Responses are wrapped in `data`. Lists are paged (`page`, `show=500`), `startDate` for deltas. `Cache` stores the full drive/charge history as JSON in `filesDir` (`drives_<car>.json`, `charges_<car>.json`), written back with the same keys as the API (`toJson`).
- `AppState.kt` — global state. `refresh()` runs on every `MainActivity.onStart`: full sync when the cache is empty (first launch / other car) or `full = true`, otherwise delta since the latest known item minus one day, merged by id.
- `Ui.kt` — formats (French), common widgets, `OsmMap` (osmdroid in `AndroidView`, MAPNIK tiles, User-Agent set in `MainActivity`), Canvas charts (`BarChart`, `LineChart`, `BatteryBar`).
- `Filters.kt` — `Period` presets + date range picker, chip helpers.
- Screens: `StatusScreen`, `DrivesScreen` (+ `DriveDetailScreen`), `ChargesScreen` (+ `ChargeDetailScreen`), `StatsScreen` (per month / per year), `SettingsScreen`. Filters are pure functions on the cached lists (`List<Drive>.filtered`, `List<Charge>.filtered`).
- AC/DC is estimated: DC if "supercharger" in address or average power > 25 kW (the list endpoint has no charger type).
