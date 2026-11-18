# CLAUDE.md

## Project

MyTeslamateApiMobile: personal Android app (Kotlin, Jetpack Compose) that reads TeslaMate data through a TeslaMate API instance (MyTeslaMate hosted `https://<sub>.api.myteslamate.com`, or self-hosted) and offers filters and statistics. UI strings and code comments are in French — keep it that way.

## Build

Same conventions as MyRadioPlayer: no Gradle wrapper (system `gradle` 9.9.1, JDK 17, compileSdk 37, targetSdk 34, AGP 9 with built-in Kotlin). CI = thin callers of `florianfish/Forge-Apk` reusable workflows (`build.yml` → debug APK + `debug` pre-release with fixed link, `release.yml` → signed release on `v*` tag, needs `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` secrets). No tests. Debug builds use `applicationIdSuffix = ".debug"` and the label « TeslaMate debug » (`resValue app_name`), so debug and release install side by side.

## Architecture

Single module `:app`, package `fr.florian.teslamate`. No networking/DI/navigation libraries: `HttpURLConnection` + `org.json`, Compose state, manual navigation.

- `TeslaMateApi.kt` — `Connection` (SharedPreferences `connexion`; Basic auth with user = URL subdomain by default, or Bearer token), blocking client (call on `Dispatchers.IO`), models, JSON parsing. Responses are wrapped in `data`. Lists are paged (`page`, `show=500`), `startDate` for deltas. `Cache` stores the full drive/charge history as JSON in `filesDir` (`drives_<car>.json`, `charges_<car>.json`), written back with the same keys as the API (`toJson`).
- Security: the API password/token is AES-GCM encrypted with a non-exportable Android Keystore key (`SecretBox`, prefs key `secret_enc`; old plaintext `secret` migrated on load). Backups and device transfer are disabled (`allowBackup=false`, `data_extraction_rules.xml`) so neither credentials nor location history leave the phone. No cleartext traffic: HTTPS only (Settings refuses `http://`).
- `AppState.kt` — global state. `refresh()` runs on every `MainActivity.onStart`: full sync when the cache is empty (first launch / other car) or `full = true`, otherwise delta since the latest known item minus one day, merged by id.
- `Ui.kt` — formats (French), common widgets, `OsmMap` (osmdroid in `AndroidView`, MAPNIK tiles, User-Agent set in `MainActivity`), Canvas charts (`BarChart`, `LineChart`, `BatteryBar`).
- `Filters.kt` — `Period` presets + date range picker, chip helpers.
- Screens: `StatusScreen`, `DrivesScreen` (+ `DriveDetailScreen`), `ChargesScreen` (+ `ChargeDetailScreen`), `StatsScreen` (per month / per year), `SettingsScreen`. Filters are pure functions on the cached lists (`List<Drive>.filtered`, `List<Charge>.filtered`).
- `Analyses.kt` — Stats sub-views (chips in `StatsScreen`): recurring routes (same start/end address ≥ 3 times, monthly seasonality), consumption vs outside temperature (drives ≥ 5 km, 5 °C bands), idle losses (battery gap between consecutive drives/charges, gaps ≥ 1 h; % → kWh/km via battery health), savings vs a thermal car (L/100 km and €/L in prefs `app`) + free charges, software updates (`/updates`, version without build hash).
- `Calendar.kt` — Stats "Calendrier" sub-view: GitHub-style heatmap (Canvas, one column per week, Monday on top, horizontally scrollable, current week scrolled into view), km driven or kWh charged per day (local start date, days < 0.5 ignored), 4 colour levels = quartiles of non-empty days. "Tout" shows one grid per year. Tapping a day sets a one-day `Period` on the Drives (km) or Charges (kWh) filter and switches tab (`MainScreen`).
- Selection maps: Drives "Carte" downloads the detail of the 40 most recent filtered drives, keeps a simplified path (80 points) in `paths_<car>.json` (`AppState.loadPaths`); Charges "Carte" shows one marker per address with totals.
- AC/DC is estimated: DC if "supercharger" in address or average power > 25 kW (the list endpoint has no charger type).
