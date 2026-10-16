# Expense Tracker: Implementation Plan

Companion to the design plan doc. This file covers how to build it: stack, repo layout, setup, and phases with concrete tasks and exit criteria.

Working name: **Tally** (rename freely; it only appears in package names and the APK label).

---

## 0. Status

_Last updated 2026-10-16. Branch `ccr-d73773ad-js0afr`._

| Phase | State |
| --- | --- |
| Scaffold (4.2) | Done |
| Phase 0: raw logger | Installed and running on the phone (S24 Ultra, Android 16). Package names confirmed. **Purchase check still pending** (no fixtures yet). |
| Phase 1: manual core | Built. Remaining: place the widget and run the "done when" check on the phone. Ledger, month and category screens checked on the phone; widget installed but not yet placed. |
| Phase 2 to 6 | Not started. Phase 2 waits for real RBC / wallet notifications. |

What's verified so far:
- `./gradlew :core:test`: 68 tests passing (amount parsing, money formatting, export format, numpad entry, chip ranking, month summary, week spend).
- `./gradlew :app:testDebugUnitTest`: 29 tests passing (Room DAO, 60 s re-post dedupe, source stats, export round trip, extras dump, v1 to v2 migration, seed data, quick-entry save and edit, delete and undo, category tree, category edits).
- `./gradlew :app:lintDebug`: no issues. `assembleDebug` and `assembleRelease` succeed.
- Debug build installed on the phone (`dev.emre.tally.debug`).

Changes from the original plan:
- **Toolchain moved to current releases**: AGP 9.4.1 with built-in Kotlin (no `org.jetbrains.kotlin.android` plugin), Gradle 9.8.1, Kotlin 2.4.21, compile/target SDK 37. See section 2.
- **`RawEventEntity` gained columns**: `postedAt`, `zoneId`, `channelId`, `isGroupSummary` (section 5). Free to add in v1, and `zoneId` follows decision 6.
- **Amount parsing helper pulled forward** from Phase 2 into Phase 0 (`core/capture/Amounts.kt`).
- **Raw event export format lives in `core`** (`RawEventExport`), so the app's export and the parser fixture tests share one definition.
- **App JVM tests use Robolectric** (JUnit 4 runner); `core` uses JUnit Jupiter (JUnit 6).
- **Debug builds install side by side** as `dev.emre.tally.debug`, labelled "Tally (debug)".
- **Backups**: cloud backup is excluded for all data; device-to-device transfer is allowed (helps phone migration).

---

## 1. Guiding decisions

1. **One shared pure-Kotlin `core` module.** Parsers, merchant normalization, rules engine, dedupe, and the statement matcher live in a JVM module with zero Android imports. The Android app and the server both depend on it. All the logic that can break is unit-testable on your laptop in milliseconds.
2. **Local-first.** The phone's Room DB is the source of truth until the server exists. The app must be fully useful with no network.
3. **Capture raw, parse later.** Every notification from a watched package is stored verbatim before any parsing. Parsers can be rewritten and re-run over history at any time.
4. **Ship the raw logger on day one.** It collects real RBC / Samsung / Google Wallet notifications in the background while you build Phase 1, so by Phase 2 you have a week or more of fixtures.
5. **minSdk = your phone.** No compat code for devices you don't own. Set to **36** (Galaxy S24 Ultra, Android 16).
6. **Money is `Long` cents. Time is `Instant` + the zone at capture.** No floats, no `LocalDateTime` in storage.
7. **No DI framework.** A single `AppContainer` created in `Application.onCreate()` is enough for one app with one developer. Hilt adds build time and ceremony you don't need.

---

## 2. Tech stack

Versions are pinned in `gradle/libs.versions.toml`.

### Android app

| Concern | Choice | Version | Notes |
| --- | --- | --- | --- |
| Build | Android Gradle Plugin | 9.4.1 | Built-in Kotlin: no `kotlin-android` plugin. Needs Gradle 9.6+ |
| Language | Kotlin, coroutines + Flow | 2.4.21 / coroutines 1.11.0 | |
| UI | Jetpack Compose, Material 3 | BOM 2026.09.00 | Dynamic color off; own palette in `ui/theme/Theme.kt`. `material-icons-core` added explicitly (Material 3 no longer pulls it in) |
| Navigation | Navigation Compose | 2.10.2 | String routes; 5 to 6 screens total |
| Persistence | Room (KSP) | Room 2.8.5, KSP 2.3.12 | `exportSchema = true`, schema JSON committed in `app/schemas/` |
| Widget | Jetpack Glance | 1.2.0 | Phase 1 |
| Background work | WorkManager | 2.12.0 | Sync, CSV import, re-parse jobs |
| Notification capture | `NotificationListenerService` | | Core of auto capture |
| Serialization | kotlinx.serialization | 1.11.0 | Raw event export, sync, TOML via ktoml |
| TOML | ktoml (`com.akuleshov7:ktoml-core`) | 0.7.1 | Fallback: tomlj if ktoml chokes on something |
| Charts (on device) | Vico | 3.3.1 | Compose-native charts |
| Settings | DataStore (Preferences) | 1.2.1 | Server URL, token, toggles |
| SDK levels | | compile 37, target 37, min 36 | |
| Testing | JUnit Jupiter in `core`; Robolectric for app JVM tests | JUnit 6.1.3; Robolectric 4.17, JUnit 4.13.2 | Room migration tests can run on Robolectric too |

### Server (Phase 4)

| Concern | Choice | Notes |
| --- | --- | --- |
| Framework | Ktor (Netty) | Same language, reuses `core` directly |
| DB | SQLite via Exposed | One user, one file, trivial backups |
| Web UI | Server-rendered kotlinx.html + HTMX + Chart.js | One deployable, no JS build step. Swap to a SPA only if it starts hurting |
| Auth | Single long random bearer token + HTTPS | Optionally only reachable over Tailscale |
| LLM | Anthropic API, `claude-haiku-5-5` | One call per unseen merchant, result cached |
| Deploy | Docker container on the home server, behind the existing reverse proxy | e.g. `spend.ems-place.com` |
| Backups | Nightly `sqlite3 .backup` to wherever Vault backups go | |

### Tooling

- Gradle **9.8.1** via the committed wrapper, Kotlin DSL, version catalog (`gradle/libs.versions.toml`).
- JDK 17+ (both modules target JVM 17 bytecode). An Android Studio recent enough for AGP 9.4.
- Git repo from minute one. `app/schemas/` (Room schema JSON) is committed.
- Optional: GitHub Actions job that runs `core` and app unit tests and builds a signed release APK on tag.

---

## 3. Repo layout

As built (Phase 0). Folders marked _later_ don't exist yet.

```
tally/
  settings.gradle.kts
  build.gradle.kts              # plugin declarations only (apply false)
  gradle.properties             # documents the TALLY_* signing properties
  gradle/libs.versions.toml
  gradle/wrapper/               # Gradle 9.8.1
  README.md
  docs/implementation-plan.md   # this file
  core/                         # pure Kotlin/JVM, no Android
    src/main/kotlin/tally/core/
      model/
        Enums.kt                # Source, Kind, TransactionStatus, CategorySource, ParseStatus
        Money.kt                # Money.format(cents)
      capture/
        Capture.kt              # RawNotification, AccountHint, Capture, ParseResult, CaptureParser
        WatchedPackages.kt      # package names -> Source, display names
        Amounts.kt              # parseCents(), findFirst()
        RawEventExport.kt       # export file format + codec
      normalize/                # later: MerchantNormalizer, alias matching
      rules/                    # later: RulesEngine, TOML codec
      dedupe/                   # later: CaptureDeduper
      reconcile/                # later: StatementMatcher, RbcCsvReader
    src/test/kotlin/...         # AmountsTest, MoneyTest, RawEventExportTest
    src/test/resources/fixtures/   # exported raw notifications, CSV samples (README only so far)
  app/                          # Android
    src/main/kotlin/tally/app/
      TallyApplication.kt       # creates AppContainer; Context.appContainer accessor
      AppContainer.kt
      MainActivity.kt
      data/                     # RawEventEntity, RawEventDao, TallyDatabase, RawEventRepository, SettingsRepository
      capture/                  # TallyNotificationListener, ExtrasJson (Bundle -> JSON)
      ui/
        TallyNavHost.kt
        theme/Theme.kt
        rawevents/              # RawEventsScreen, RawEventDetailScreen, RawEventExport (file + share sheet)
        ledger/                 # LedgerScreen (start destination), MonthScreen, LedgerFormat
        categories/             # CategoriesScreen (list + add/edit dialog)
        setup/                  # SetupScreen, SetupStatus (+ settings intents)
      quickentry/               # QuickEntryActivity, QuickEntrySheet, QuickEntryTileService, QuickAddShortcut
      widget/                   # QuickAddWidget (Glance) + receiver
      sync/                     # later: SyncWorker (Phase 4)
    src/main/res/xml/           # file_paths.xml (FileProvider), data_extraction_rules.xml
    src/test/kotlin/...         # Robolectric: RawEventRepositoryTest, ExtrasJsonTest
    schemas/                    # Room schema JSON, committed
  server/                       # later: Ktor (Phase 4)
    src/main/kotlin/tally/server/
    Dockerfile
```

`core` is a plain `kotlin("jvm")` module. The Android app depends on it with `implementation(project(":core"))`.

---

## 4. Initial setup

Do these in order. Total time: one evening.

### 4.1 Phone and bank setup

1. **RBC alerts.** RBC Mobile, More, Settings, Manage Alerts:
   - Credit card: transaction alerts, threshold $1, delivery: push.
   - Chequing: withdrawal alerts and deposit alerts, threshold $1, delivery: push.
2. **Wallet notifications.** Make sure Samsung Wallet and Google Wallet both have notifications enabled in Android settings (transaction notifications specifically, if they're separate channels).
3. **Developer options + wireless debugging.** Settings, About phone, Software information, tap Build number 7 times. Enable USB debugging and Wireless debugging. Pair once with `adb pair`, then `adb connect` from then on.
4. **Samsung background killing.** This matters a lot on Samsung. For the Tally app (after first install):
   - Settings, Apps, Tally, Battery: **Unrestricted**.
   - Settings, Battery, Background usage limits: add Tally to **Never sleeping apps**.
   - Without this, One UI will kill the notification listener and you will silently miss captures.

### 4.2 Project scaffold ✅

Done. What exists:

1. Gradle multi-module project: `core`, `app` (`server` later).
2. Version catalog with: AGP, Kotlin, KSP, Compose BOM, Room, Glance, WorkManager, Navigation Compose, kotlinx.serialization, coroutines, ktoml, Vico, DataStore, JUnit (Jupiter + 4), Robolectric, androidx.test.
3. `app/build.gradle.kts`:
   - `namespace = "tally.app"`, `applicationId = "dev.emre.tally"`, `minSdk = 36`, `compileSdk = targetSdk = 37`.
   - Debug build type: `applicationIdSuffix = ".debug"`, app name "Tally (debug)". Release: app name "Tally", no minification.
   - Room: `room { schemaDirectory("$projectDir/schemas") }`.
   - Release signing reads from `~/.gradle/gradle.properties` and is only configured when `TALLY_KEYSTORE_PATH` is set:
     ```properties
     TALLY_KEYSTORE_PATH=/path/to/tally-release.jks
     TALLY_KEYSTORE_PASSWORD=...
     TALLY_KEY_ALIAS=tally
     TALLY_KEY_PASSWORD=...
     ```
     Keystore lives outside the repo; back it up. Lose it and you can't update the installed app without uninstalling (and losing local data if sync isn't running yet).
   - Unit tests: `isIncludeAndroidResources = true`, and `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` (Robolectric's Android 16+ runtime needs it on modern JDKs).
4. `core/build.gradle.kts`: `kotlin("jvm")`, kotlinx.serialization plugin, JVM 17 target, JUnit Jupiter.
5. `.gitignore` covers Gradle/Android outputs, `local.properties`, keystores; keeps `gradle-wrapper.jar`.

### 4.3 First install and permissions

Sideloaded apps need a few one-time steps:

1. `./gradlew :app:installDebug` (or install the release APK).
2. **Allow restricted settings.** Android 13+ blocks notification-listener access for sideloaded apps until you enable it: Settings, Apps, Tally, three-dot menu, **Allow restricted settings**.
3. **Notification access.** The app deep-links with `Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` (falls back to `ACTION_NOTIFICATION_LISTENER_SETTINGS`).
4. **Post notifications** permission (runtime prompt), for capture notifications. If denied permanently, the button opens the app's notification settings.
5. Battery settings from 4.1 step 4. The app asks via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (lint's Play-policy warning is suppressed; this app is sideloaded).

The in-app **Setup** screen (gear icon on the raw events screen) is built ✅. It shows green/amber status for each item, re-checked every time the screen resumes:
- Notification access (`NotificationManagerCompat.getEnabledListenerPackages`).
- Allow restricted settings (inferred from notification access; links to App info).
- Post notifications (`areNotificationsEnabled`).
- Battery unrestricted (`PowerManager.isIgnoringBatteryOptimizations`).
- Samsung "Never sleeping apps" (can't be detected; shown as a manual step).
- Quick add tile: tracked by the tile service (`onTileAdded` / `onTileRemoved` / `onStartListening`, stored in DataStore); "Add" shows the system prompt via `StatusBarManager.requestAddTileService`.

It also holds the "Log all packages" toggle and listener health (section 9).

---

## 5. Data model (Room)

All IDs are UUID strings (generated on device, so sync never needs ID remapping). Every syncable table has `updatedAt: Long` (epoch ms) and `deleted: Boolean` (soft delete for sync).

**Database v2 (built).** v1 had `raw_events` only; v2 adds every table below through a Room `AutoMigration(1, 2)`, tested in `TallyDatabaseMigrationTest`. No foreign key constraints: sync can deliver rows in any order, so references are plain indexed columns.

```kotlin
// Built (v1). Table "raw_events", indexed on receivedAt, sbnKey, source.
@Entity data class RawEventEntity(
    @PrimaryKey val id: String,
    val source: String,               // package name or "csv:<file>"
    val receivedAt: Long,             // epoch ms when Tally received it
    val postedAt: Long,               // NEW: StatusBarNotification.postTime
    val zoneId: String,               // NEW: device zone at capture
    val sbnKey: String?,              // StatusBarNotification.key, for dedupe of re-posts
    val channelId: String?,           // NEW: notification channel
    val isGroupSummary: Boolean,      // NEW: FLAG_GROUP_SUMMARY; wallets often post summaries that repeat the text
    val title: String?,
    val text: String?,
    val bigText: String?,
    val extrasJson: String,           // full extras dump, for parsers you haven't written yet
    val parseStatus: String,          // PENDING | PARSED | IGNORED | FAILED
    val transactionId: String?,
)

// Built: query result, not a table. Per-package capture stats for the health check.
data class SourceStats(val source: String, val eventCount: Int, val lastReceivedAt: Long)
```

Built in v2 (Phase 1). Table names: `transactions`, `splits`, `tags`, `transaction_tags`, `categories`, `accounts`, `merchants`, `aliases`, `rules`.

```kotlin
@Entity data class TransactionEntity(
    @PrimaryKey val id: String,
    val amountCents: Long,            // positive = money out, negative = money in (refunds, deposits)
    val currency: String,             // "CAD"
    val occurredAt: Long,             // epoch ms
    val zoneId: String,               // "America/Toronto"
    val accountId: String?,
    val merchantId: String?,
    val merchantRaw: String?,         // as captured, before normalization
    val categoryId: String?,
    val note: String?,
    val source: String,               // MANUAL | RBC_ALERT | SAMSUNG_WALLET | GOOGLE_WALLET | CSV | EMAIL
    val status: String,               // CAPTURED | CONFIRMED | RECONCILED
    val categorySource: String?,      // RULE | MEMORY | LLM | MANUAL
    val kind: String,                 // EXPENSE | INCOME | TRANSFER
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Entity data class MerchantEntity(
    @PrimaryKey val id: String,
    val name: String,                 // normalized display name
    val lastCategoryId: String?,
    val confirmCount: Int,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Entity data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val monthlyBudgetCents: Long?,
    val sortOrder: Int,
    val pinned: Boolean,              // shows on widget / quick sheet
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Entity data class RuleEntity(
    @PrimaryKey val id: String,
    val priority: Int,                // lower runs first
    val merchantPattern: String,      // regex, matched against normalized name
    val minAmountCents: Long?,
    val maxAmountCents: Long?,
    val accountId: String?,
    val categoryId: String,
    val tagsCsv: String?,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Entity data class AliasEntity(        // raw pattern -> normalized merchant name
    @PrimaryKey val id: String,
    val pattern: String,
    val merchantName: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Entity data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,                 // "RBC Visa", "RBC Chequing", "Cash"
    val last4: String?,
    val walletSource: String?,        // which wallet maps to this account (SAMSUNG_WALLET -> Visa)
    val updatedAt: Long,
    val deleted: Boolean = false,
)

// Plus: TagEntity (id, name), TransactionTagCrossRef (PK transactionId + tagId),
// SplitEntity (transactionId, amountCents, categoryId, owedBy). All with updatedAt + deleted.
```

The string enum columns store the `name` of the enums in `core/model/Enums.kt` (`Source`, `Kind`, `TransactionStatus`, `CategorySource`, `ParseStatus`). Never rename an enum entry.

`kind` exists because deposit alerts give you income, and chequing alerts will also catch transfers to your own credit card (a payment, not spending). Those must be `TRANSFER` or your totals double-count.

---

## 6. Core interfaces

Built (`core/capture/Capture.kt`):

```kotlin
data class RawNotification(
    val packageName: String, val title: String?, val text: String?,
    val bigText: String?, val postedAt: Instant,
)

data class AccountHint(val last4: String? = null, val accountName: String? = null)

data class Capture(
    val amountCents: Long, val merchantRaw: String?, val occurredAt: Instant,
    val source: Source, val accountHint: AccountHint?, val kind: Kind,
)

sealed interface ParseResult {
    data class Parsed(val capture: Capture) : ParseResult
    data class Ignored(val reason: String) : ParseResult   // declined, promo, balance alert...
    data class Failed(val reason: String) : ParseResult    // watched package, no known shape: inbox flag
}

interface CaptureParser {
    val packageName: String
    fun parse(n: RawNotification): ParseResult
}
```

Also built:

```kotlin
// core/capture/Amounts.kt
object Amounts {
    fun parseCents(raw: String): Long?   // "$1,234.56", "CAD 4.25", "C$4.25", "-$5.00", "($5.00)", "4,25 $", "1 234,56 $"
    fun findFirst(text: String): Long?   // first amount in free text; requires $ or CAD so "ending in 1234" never matches
}

// core/capture/WatchedPackages.kt
object WatchedPackages {
    const val RBC = "com.rbc.mobile.android"
    const val SAMSUNG_WALLET = "com.samsung.android.spay"
    const val GOOGLE_WALLET = "com.google.android.apps.walletnfcrel"
    val ALL: Set<String>
    fun sourceFor(packageName: String): Source?
    fun displayName(packageName: String): String
}

// core/capture/RawEventExport.kt: the export file format, shared by the app and fixture tests
data class RawEventExport(val version: Int = 1, val exportedAt: Long, val events: List<ExportedRawEvent>)
data class ExportedRawEvent(/* same fields as RawEventEntity, extras as a JsonObject */) {
    fun toRawNotification(): RawNotification   // postedAt comes from the notification's post time
}
object RawEventExportCodec { fun encode(...): String; fun decode(text: String): RawEventExport }  // ignores unknown keys

// core/model/Money.kt
object Money { fun format(cents: Long): String }   // 123456 -> "$1,234.56", -310 -> "-$3.10"
```

Still to build:

```kotlin
// core/normalize
class MerchantNormalizer(private val aliases: List<Alias>) {
    fun normalize(raw: String): String   // aliases first, then generic cleanup
}

// core/rules
class Categorizer(rules: List<Rule>, memory: MerchantMemory, llm: LlmCategorizer?) {
    fun categorize(c: NormalizedCapture): Suggestion   // category + source + confidence
}

// core/dedupe
class CaptureDeduper(private val window: Duration = 10.minutes) {
    fun findMatch(new: Capture, recent: List<Transaction>): Transaction?
}

// core/reconcile
class StatementMatcher { fun match(rows: List<StatementRow>, txns: List<Transaction>): MatchResult }
```

The Android listener does nothing clever: store raw event, call `core`, write the result. All branching logic lives in `core`.

---

## 7. Phases

Rough effort estimates assume evenings and weekends around school and RA hours.

### Phase 0: Skeleton + raw logger (1 to 2 evenings), code complete

**Goal:** a debuggable app on your phone that silently records every notification from bank and wallet apps.

Tasks:
- [x] Scaffold from section 4.2.
- [x] Install on phone, complete section 4.3.
- [x] Room DB with `RawEventEntity` only (more tables come in Phase 1 via migration; this exercises the migration path early). Schema v1 committed.
- [x] `TallyNotificationListener : NotificationListenerService`:
  - [x] Watched packages in `core`'s `WatchedPackages`. Starting guesses, **verify against what actually shows up**: RBC Mobile `com.rbc.mobile.android`, Samsung Wallet `com.samsung.android.spay`, Google Wallet `com.google.android.apps.walletnfcrel`.
  - [x] Debug toggle "Log all packages" (DataStore, exposed as a `StateFlow` the listener reads synchronously). Unknown packages show under "Other packages seen" on the Setup screen.
  - [x] In `onNotificationPosted(sbn)`: ignores Tally's own notifications; reads `EXTRA_TITLE`, `EXTRA_TEXT`, `EXTRA_BIG_TEXT`, post time, channel, group-summary flag, zone; dumps all extras to JSON (`ExtrasJson`: nested bundles and arrays recurse, opaque Parcelables become a short description, unreadable keys become an error string instead of failing).
  - [x] Skips if the same `sbn.key` + identical title/text/bigText was stored in the last 60 s. The check and the insert run under a mutex in `RawEventRepository`; the SQL uses `IS` so nulls compare equal. Events with no key are never deduped.
  - [x] DB writes on a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` owned by the service, cancelled in `onDestroy`. Read or store failures are logged, never crash the listener.
  - [x] In `onListenerDisconnected()`, call `requestRebind(ComponentName(...))`.
- [x] **Raw events screen:** list newest first with source, time, title, text (marks group summaries). Tap to see all fields plus pretty-printed extras JSON (selectable, monospace).
- [x] **Export button** (share icon): writes all raw events, oldest first, as a `RawEventExport` JSON file to `cache/exports/` (only the latest export is kept) and opens the share sheet through a `FileProvider`. This is how fixtures get into `core/src/test/resources/fixtures/`.
- [x] Setup checklist screen (section 4.3) with listener health.
- [x] (Pulled forward from Phase 2) Amount parsing helper, `Amounts`, with tests.

**Done when:** you buy something with Samsung Pay and with the physical card, and both the RBC alert and the wallet notification appear in the raw events screen. **Installed; purchase check still pending.**

Leave it running. Everything you buy from now on becomes test data.

---

### Phase 1 (M1): Manual core (1 to 2 weekends)

**Goal:** logging cash takes about 3 seconds, from anywhere.

Tasks:

**Schema**
- [x] Add all entities from section 5 via Room migration v1 to v2 (`AutoMigration`). Migration test on Robolectric with the driver-based `MigrationTestHelper` (the SupportSQLite one rejects absolute paths on Windows). Schemas are added to debug assets because Robolectric only reads the app variant's merged assets.
- [x] Seed default accounts (RBC Visa, RBC Chequing, Cash) and a starter category tree on first run (`DefaultData`). Seeded ids are derived from the name and `updatedAt = 0`, so a reinstall + sync lines up with the synced rows and real edits win. Tree: Food (Groceries, Dining out, Delivery, Coffee, Convenience), Shopping (Online, In store), Transport (Transit, Rideshare), Bills (Phone, Subscriptions), Entertainment, Health. Pinned: Coffee, Convenience, Dining out, Groceries.

**Quick-entry sheet**
- [x] `QuickEntryActivity` with a translucent theme (`windowIsTranslucent`, no title, no window dim; the sheet draws its own scrim, and window animations are off so only the sheet animates). Content is a Compose `ModalBottomSheet` that opens expanded.
- [x] Custom on-screen numpad (Compose grid), not the system keyboard: no IME animation delay, and amounts entered as cents (typing `425` shows `$4.25`; display via `Money.format`). Logic in `core/entry/AmountEntry` (max $99,999,999.99). Keys: 1 to 9, 00, 0, backspace (hold to clear).
- [x] Category chip row below the numpad: pinned categories first, then ordered by usage frequency in the current time-of-day bucket (morning / afternoon / evening / night) over the last 60 days. Logic in `core/entry/CategoryRanking`; ties fall back to tree order. Chips are subcategories plus top-level categories without children.
- [x] Tapping a chip saves and finishes the activity. Optional note field collapsed behind a small icon. Account defaults to Cash for manual entries; change it from the account dropdown at the top of the sheet (more discoverable than a long-press). Saving shows a toast "Saved $4.25 · Coffee".
- [x] Accepts intent extras: preselected category, prefilled amount, existing transaction id (edit mode). Phase 2 reuses this for "Change". `QuickEntryActivity.intent(context, categoryId, amountCents, transactionId)`. Preselected or current category shows as a selected chip and a full-width "Save · Coffee" button, so a widget button is type amount + one tap.
- [x] `setShowWhenLocked(true)` so it works over the lock screen when launched from the tile. Opens over the lock screen; saving from there still needs a manual check.

**Launch points**
- [x] Quick Settings tile: `TileService` whose `onClick` calls `startActivityAndCollapse(PendingIntent)` (the PendingIntent overload is required on API 34+).
- [x] App shortcut (long-press icon) "Quick add": published at runtime with `ShortcutManagerCompat.pushDynamicShortcut` instead of a static `shortcuts.xml`, because static shortcuts hard-code the package name and the debug build uses `dev.emre.tally.debug`.
- [x] Glance widget (4x2): this week's spend vs. weekly budget at the top, 4 pinned category chips below. Each chip uses `actionStartActivity<QuickEntryActivity>` with the category extra. Refresh via `GlanceAppWidget.updateAll()` after every write. Built as `QuickAddWidget`: starred categories in a 2x2 grid (four names in one row get cut off at 4x2); header opens the app, the ⊕ opens quick add with no category. Budget bar only shows once budgets exist (Phase 6); weekly budget = top-level monthly budgets x 12 / 52 (`core/report/WeekSpend`). Refresh: `AppContainer` watches transactions + categories and calls `updateAll()` (debounced 500 ms) instead of calling it after each write; `updatePeriodMillis` = 1 h rolls the week over. Each button sets a distinct data URI so its PendingIntent is unique.

**Screens**
- [x] Ledger: transactions grouped by day, swipe to delete (with undo snackbar), tap to edit. Becomes the start destination; raw events move behind a debug menu item. Overflow menu: Categories, Setup, Raw events. FAB opens quick entry. Day headers show the day's net spending.
- [x] Simple month view: total + per-category totals (plain list; charts come in Phase 6). Logic in `core/report/MonthSummary`: net expenses (refunds subtract, transfers ignored), income separately, top-level categories with subcategory rows and a share bar.
- [x] Category manager: add, rename, reorder, pin, set parent. `CategoryRepository`: names unique among siblings, no "/" (paths use it), two levels only, a parent with subcategories can't move or be deleted. Deletes are soft; old transactions keep the name. Star only shown on chip categories.

**Done when:** from a locked phone, swipe down, tap tile, type amount, tap chip, and the transaction is saved in about 3 seconds. Same from the widget.

---

### Phase 2 (M2): Auto capture (2 weekends)

**Goal:** card and chequing purchases log themselves, once each, with sensible categories.

Prerequisite: at least a week of raw events from Phase 0, exported into `core/src/test/resources/fixtures/` (scrub card numbers beyond last 4 first).

**Parsers (in `core`)**
- [ ] Read the fixtures and write down every distinct notification shape per package (purchase, refund, deposit, withdrawal, e-Transfer, payment to card, declined, promotional junk). Check whether group summaries duplicate the real notification and should be `Ignored`.
- [ ] `RbcParser`: one regex per shape. Extract amount, merchant (if present), card last4 or account name, and `kind`. Payments to your own credit card from chequing = `TRANSFER`. Declined and promo = `Ignored`.
- [ ] `SamsungWalletParser`: amount, merchant, card hint.
- [ ] `GoogleWalletParser`: same; lower priority, but do it in this phase if shapes are simple.
- [x] Amount parsing helper: handles `$1,234.56`, `CAD 4.25`, French formats (`4,25 $`, `1 234,56 $`). Done in Phase 0 as `Amounts`; extend its tests with real strings from fixtures.
- [ ] **Golden tests:** each fixture file (`RawEventExport` format, read with `RawEventExportCodec` and `toRawNotification()`) has a sibling `.expected.json`. A test iterates all fixtures and asserts parser output. Adding a new case = drop two files in a folder.

**Normalization (in `core`)**
- [ ] `MerchantNormalizer.normalize(raw)`:
  1. Apply user aliases (regex, first match wins).
  2. Generic cleanup: strip trailing store numbers (`#1234`, ` 01234`), trailing city + province codes (`HAMILTON ON`), payment processor prefixes (`SQ *`, `TST* `, `PAYPAL *`), collapse whitespace, title-case.
- [ ] Unit tests with real merchant strings from fixtures.

**Dedupe (in `core`)**
- [ ] `CaptureDeduper.findMatch`: candidate = existing transaction with the same `amountCents`, `occurredAt` within 10 min, compatible account (wallet source maps to account via `AccountEntity.walletSource`), not already merged with an event from the same source.
- [ ] Merge policy: first event creates the transaction. Later events attach their raw event and fill in missing fields. Wallet merchant name replaces RBC's if RBC's looks truncated or uppercase-mangled. RBC event sets the account authoritatively.
- [ ] Tests: wallet then RBC, RBC then wallet, two genuine identical purchases 2 minutes apart (should NOT merge if both come from the same source).

**Categorization v1 (in `core`)**
- [ ] `Categorizer`: rules (Phase 3 fills these in; empty list for now) then merchant memory (`MerchantEntity.lastCategoryId`) then none.
- [ ] Confidence: memory with `confirmCount >= 2` = high (log silently). Memory with 1 = medium (notify). No match = low (notify + inbox).

**Android wiring**
- [ ] Listener pipeline: store raw, pick parser by package, parse, normalize, dedupe, categorize, write transaction with `status = CAPTURED`, mark raw event `PARSED` / `IGNORED` / `FAILED`.
- [ ] `FAILED` from a watched package = an inbox flag ("couldn't parse a notification from RBC") with a link to the raw event. This is how you find out RBC changed their wording.
- [ ] Re-parse action on the raw events screen: re-runs all `FAILED` (or all) events through the current parsers. Idempotent thanks to `RawEventEntity.transactionId`.
- [ ] **Capture notification** (own low-importance channel): "$4.25 Tim Hortons, Food/Coffee". Actions:
  - Change: opens `QuickEntryActivity` in edit mode.
  - Split: opens the transaction detail at the split section.
  - Ignore: broadcast receiver, soft-deletes the transaction.
  - Tapping the body confirms (status `CONFIRMED`, bumps merchant memory).
- [ ] High-confidence captures post no notification at all; they land as `CONFIRMED` directly.
- [ ] Merchant memory updates on every confirm or category change.

**Done when:** a week of normal spending produces one transaction per real purchase (no duplicates from wallet + RBC), and you touched fewer than a third of them.

---

### Phase 3 (M3): Rules and inbox (1 to 2 weekends)

**Goal:** you control categorization fully, and cleanup is a weekly one-minute swipe session.

**Rules engine (in `core`)**
- [ ] `RulesEngine.apply(capture)`: rules sorted by `priority`, first match wins. Match = merchant regex on normalized name AND optional amount range AND optional account.
- [ ] Order in `Categorizer`: rules, then memory, then LLM (Phase 4), then none.
- [ ] Regex compiled once and cached; invalid regex rejected at save time, never at match time.

**TOML codec (in `core`)**
- [ ] Schema:
  ```toml
  [categories]
  Food = ["Groceries", "Eating out", "Coffee"]
  Transport = ["Transit", "Uber", "Gas"]

  [[alias]]
  match = "(?i)tim hortons.*"
  name = "Tim Hortons"

  [[rule]]
  merchant = "Tim Hortons"
  category = "Food/Coffee"

  [[rule]]
  merchant = "Uber"
  max_amount = 15.00
  category = "Transport/Uber"
  tags = ["rideshare"]
  ```
- [ ] `export(db) -> String` and `import(text) -> ValidationResult`. Validation errors carry line numbers: unknown category, bad regex, negative amount, duplicate alias.
- [ ] Import is replace-all inside one DB transaction. Rule priority = order in the file.
- [ ] Round-trip test: export, import, export again; strings equal.

**Android**
- [ ] **"Always do this"** action on transaction detail and on inbox cards: creates a rule from the normalized merchant + chosen category. Optional checkbox "only under $X" prefilled with the current amount rounded up. Asks once whether to re-categorize past transactions from that merchant.
- [ ] **Rules screen:** list in priority order, drag to reorder, tap to edit in a form. Toolbar button "Edit as TOML".
- [ ] **TOML editor screen:** monospace text field, Validate button showing errors inline by line, Save disabled until valid. Plain text editing is fine; it's for bulk changes.
- [ ] **Alias manager:** list + "create alias from this transaction" shortcut (prefills the raw merchant string as an escaped regex).
- [ ] **Tags:** add/remove on transaction detail; filter ledger by tag.
- [ ] **Splits:** split a transaction into N parts with category each, or mark a portion `owedBy` a person (for "friend owes me" tracking; simple text name, no contacts integration).
- [ ] **Review inbox:**
  - Contents: `CAPTURED` low/medium confidence transactions, parse failures, (Phase 5) unmatched statement rows and stale unmatched transactions.
  - Card UI: merchant, amount, suggested category. Swipe right = accept, swipe left = category picker, tap = detail.
  - Badge count on the widget and in the app.

**Done when:** you can define all your categorization in rules + aliases, edit them on the phone in either form or TOML, and the inbox empties in under a minute a week.

---

### Phase 4 (M4): Server, sync, web, LLM (2 to 3 weekends)

**Goal:** data backed up off-phone, big-screen dashboard and rule editing, LLM handles unknown merchants.

**Server**
- [ ] `server` module: Ktor + Exposed + SQLite, depends on `core`.
- [ ] Tables mirror the syncable Room entities (same UUIDs, same `updatedAt` / `deleted` columns). Raw events sync too (useful for re-parsing on the server later and for backups).
- [ ] Auth: `Authorization: Bearer <token>` checked by a Ktor plugin. Token generated once, stored in server env and in the app's DataStore.
- [ ] Dockerfile (multi-stage: Gradle build, then a JRE base image). Volume for the SQLite file. Deploy behind the existing reverse proxy with HTTPS.
- [ ] Nightly backup job.

**Sync protocol** (single user, simple on purpose)
- [ ] `POST /sync` with `{ deviceId, since: serverCursor, changes: [ {table, row} ... ] }`.
- [ ] Server: applies each incoming row if `incoming.updatedAt > stored.updatedAt` (last write wins), assigns a monotonically increasing `serverSeq` to every write, returns all rows with `serverSeq > since` plus the new cursor.
- [ ] Client: `SyncWorker` (WorkManager), periodic every 1 h on unmetered or charging + on app foreground + after each capture (debounced 30 s). Tracks dirty rows via a `dirty` flag or a local outbox table.
- [ ] Clock-skew guard: the server rejects `updatedAt` more than 5 min in the future.
- [ ] Tests: offline edits on both sides, delete vs. edit conflict (later timestamp wins), first full sync from an existing phone DB.

**LLM categorization**
- [ ] Server endpoint `POST /categorize { merchant, amountCents, categories[] }`, called by the phone for low-confidence captures when online.
- [ ] Prompt: category list (full paths), normalized merchant, amount; ask for exactly one category path or `UNKNOWN`. Validate the answer is in the list.
- [ ] Cache by normalized merchant on the server. Never call twice for the same merchant.
- [ ] Result enters as `categorySource = LLM`, still goes to the inbox once for confirmation, then becomes merchant memory.

**Web dashboard** (kotlinx.html + HTMX + Chart.js)
- [ ] Ledger table with filters (date range, category, tag, account, text search), inline category edit.
- [ ] Monthly overview: spend by category (bar), daily cumulative vs. last month (line), income vs. spend.
- [ ] Rules page: same TOML editor with validation, plus the form list. Changes sync to the phone like any other rows.
- [ ] Inbox page for when you'd rather triage on the laptop.

**Optional in this phase:** Gmail parsers for itemized receipts (Gmail API with OAuth in "testing" mode, your account as the only test user). Only worth it if you find yourself wanting order-level detail.

**Done when:** you can wipe and reinstall the app, sync, and get everything back; and you can do a monthly review entirely on the web.

---

### Phase 5 (M5): Statement reconciliation (1 weekend)

**Goal:** a monthly sanity check that catches anything the alerts missed and corrects pending amounts.

- [ ] `RbcCsvReader` in `core`: parse RBC's CSV export (reuse `Amounts.parseCents`). Check whether it has a stable transaction ID column; if yes, store it on matched transactions and use it to make re-imports idempotent.
- [ ] `StatementMatcher`: for each statement row, score candidates on the same account:
  - Date within 3 days (score falls off with distance).
  - Amount: exact = full score; within 25% for categories tagged "variable" (restaurants, gas: tips and pre-auth holds) = partial.
  - Merchant similarity after normalization (token overlap or Jaro-Winkler).
  - Greedy assignment by best score, one-to-one.
- [ ] Outcomes:
  - Match: set `status = RECONCILED`, adopt the statement's final amount, keep your category.
  - Statement row with no match: create a transaction (`source = CSV`), categorize, send to inbox.
  - Your transaction with no match and older than 7 days: inbox flag.
  - Refund rows: link to the original purchase where merchant + amount match.
- [ ] Import UI: on phone (Storage Access Framework file picker) and on web (upload). Show a summary before committing: N matched, N new, N flagged.
- [ ] Golden tests with a sanitized real CSV.

**Done when:** importing a month's statement produces zero surprises you can't explain from the inbox.

---

### Phase 6 (M6): Budgets and insights (1 weekend, then ongoing)

- [ ] Monthly budget per category (already in schema); weekly budget derived for the widget.
- [ ] Widget progress bar: week spend vs. budget, color shift past 80% and 100%.
- [ ] On-device stats screen with Vico: category breakdown, month over month, top merchants.
- [ ] Income vs. spend from deposit alerts (`kind = INCOME`), net per month.
- [ ] Recurring detection: same merchant + similar amount at ~monthly interval gets flagged as a subscription; list them on one screen with yearly cost.
- [ ] "Owed to me" view from splits with `owedBy`, with a mark-as-settled action.

---

## 8. Testing strategy

- **`core` carries the weight.** Parsers, normalizer, dedupe, rules, TOML codec, and matcher are all pure functions with JUnit Jupiter tests (`./gradlew :core:test`). Target: every bug you ever hit becomes a fixture. Today: `AmountsTest`, `MoneyTest`, `RawEventExportTest`, `AmountEntryTest`, `CategoryRankingTest`, `MonthSummaryTest`, `WeekSpendTest` (68 tests).
- **App JVM tests on Robolectric** (`./gradlew :app:testDebugUnitTest`) for Room DAOs, repositories and Android-type helpers, with an in-memory Room DB. Today: `RawEventRepositoryTest`, `ExtrasJsonTest`, `DefaultDataTest`, `TallyDatabaseMigrationTest`, `LedgerRepositoryTest`, `CategoryTreeTest`, `CategoryRepositoryTest` (29 tests). Use Room's `useWriterConnection { it.immediateTransaction { } }` for transactions, not `withTransaction`: the latter fails under the driver-based migration helper. Robolectric needs the `--add-opens` flag already set in `app/build.gradle.kts`.
- **Golden fixtures** are real exported notifications (`RawEventExport` files) and CSV rows. Scrub card numbers beyond last4 before committing (the repo should still be private).
- **Room migration tests** for every schema version, using the committed schema JSON in `app/schemas/`.
- **Lint** (`./gradlew :app:lintDebug`) stays at zero issues; suppress only with a comment saying why.
- **Manual smoke test checklist** before each release APK: tile from lock screen, widget chip, a wallet tap, a physical card swipe, inbox swipe, sync.

---

## 9. Operational notes

- **Release flow:** bump `versionCode`, `./gradlew :app:assembleRelease`, `adb install -r`. Debug and release already install side by side (`dev.emre.tally.debug` vs `dev.emre.tally`); each has its own database and needs its own notification access.
- **Listener health:** built from the raw events table: the Setup screen shows count and last-seen time per watched package (`SourceStats`). Still to add: if RBC has been silent for 3+ days while you've logged spending, show a warning (alerts may have been turned off, or the listener died). Needs Phase 1 transactions.
- **Backups and phone migration:** `allowBackup="false"` plus `data_extraction_rules.xml`: nothing goes to cloud backup; device-to-device transfer is allowed, so a new-phone transfer can carry the DB. Before Phase 4, also keep a manual DB export (Room file copy via a debug menu) as a fallback. After Phase 4, sync handles it.
- **Privacy:** everything stays on your phone and your server. The only third-party call is the LLM categorization, which sends a merchant name, an amount, and your category names; nothing else. Raw event exports contain full notification text and extras; treat them as sensitive.

---

## 10. Open items to resolve during build

- [ ] **Run Phase 0 on the phone** and confirm the "done when" check.
- [x] Set `minSdk` to the phone's API level: 36.
- [x] Exact package names for RBC, Samsung Wallet, Google Wallet: the guesses in `WatchedPackages` match what is installed (`pm list packages`). Samsung also has `com.samsung.android.spayfw` (framework); confirm which one posts the payment notification.
- [ ] RBC alert wording for each event type (Phase 0 raw logs).
- [ ] Whether wallets post group-summary notifications that duplicate the real one (`isGroupSummary` is recorded so parsers can decide).
- [ ] Whether RBC's CSV has a stable transaction ID (Phase 5).
- [ ] Whether chequing alerts distinguish debit purchases, e-Transfers, bill payments, and credit card payments clearly enough to set `kind` automatically, or whether that needs rules.
