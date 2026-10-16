# Tally

Personal expense tracker for Android. Local-first, captures bank and wallet notifications
automatically, with a quick-entry sheet for cash.

## Modules

| Module | What | Status |
| --- | --- | --- |
| `core` | Pure Kotlin/JVM: models, amount parsing, raw event export format. Parsers, normalizer, rules, dedupe and statement matching land here. | Phase 0 |
| `app` | Android app: Room DB, notification listener, Compose UI. | Phase 0 |
| `server` | Ktor sync + web dashboard. | Phase 4, not started |

## Build

Requires JDK 17+ and the Android SDK (Android Studio is easiest).

```sh
./gradlew :core:test          # fast, no Android needed
./gradlew :app:installDebug   # installs "Tally (debug)" as dev.emre.tally.debug
```

`minSdk` is set to 34 in `app/build.gradle.kts`. Change it to your phone's API level.

After the first build, commit the generated Room schema in `app/schemas/`.

### Release signing

Put these in `~/.gradle/gradle.properties` (never in the repo) and back the keystore up:

```properties
TALLY_KEYSTORE_PATH=/path/to/tally-release.jks
TALLY_KEYSTORE_PASSWORD=...
TALLY_KEY_ALIAS=tally
TALLY_KEY_PASSWORD=...
```

Then `./gradlew :app:assembleRelease`.

## First install on the phone

1. Install, open Tally, tap the gear icon for **Setup**.
2. **Allow restricted settings**: App info, three-dot menu, Allow restricted settings
   (Android 13+ blocks notification access for sideloaded apps until you do this).
3. **Notification access**: enable Tally.
4. **Post notifications**: allow.
5. **Battery**: unrestricted, and on Samsung add Tally to Never sleeping apps
   (Settings, Battery, Background usage limits).

## Phase 0: raw logger

Every notification from a watched package (RBC, Samsung Wallet, Google Wallet; see
`core/.../capture/WatchedPackages.kt`) is stored verbatim in the `raw_events` table. Re-posts
of the same notification with identical text within 60 s are dropped.

- Turn on **Log all packages** in Setup for the first day to confirm the real package names
  (they show up under "Other packages seen"), then fix `WatchedPackages` if needed.
- The share icon on the raw events screen exports everything as JSON
  (`RawEventExport` in `core`). Scrub card numbers beyond the last 4 digits, then drop the
  files into `core/src/test/resources/fixtures/` for Phase 2 parser tests.
