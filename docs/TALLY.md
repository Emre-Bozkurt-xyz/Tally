# Expense Tracker: Implementation Plan

Companion to the design plan doc. This file covers how to build it: stack, repo layout, setup, and phases with concrete tasks and exit criteria.

Working name: **Tally** (rename freely; it only appears in package names and the APK label).

---

## 0. Status

_Last updated 2026-10-16. Branch `ccr-d73773ad-js0afr`._

| Phase | State |
| --- | --- |
| Scaffold (4.2) | Done |
| Phase 0: raw logger | Done for Google Pay + debit: on 2026-10-16 both the Google Wallet and RBC notifications were captured (first fixture). Still unseen: Samsung Pay, physical card, credit card. |
| Phase 1: manual core | Built. Remaining: place the widget and run the "done when" check on the phone. Ledger, month and category screens checked on the phone; widget installed but not yet placed. |
| Phase 2: auto capture | Wired end to end: notification → parse → match the wallet/RBC pair → merchant cleanup → memory categorization → capture notification (Change / Ignore / tap to confirm). Parsers know 2 shapes so far (RBC chequing withdrawal, Google Wallet purchase); more as fixtures arrive. Not yet run on a real purchase after wiring. |
| Phase 3: rules and inbox | Built: rules engine + TOML file (own parser, errors by line), rules / TOML / aliases screens, review inbox with swipe actions and "always do this", transaction detail with tags and splits. Installed; the "done when" check needs a few weeks of real use. |
| Phase 6: budgets and insights | Built: budgets (categories dialog; month view and widget with 80% / 100% colors), Stats (6-month chart, net per month, top merchants), Subscriptions (detected), Owed to me (settle / undo). |
| Phase 4: server | Step 1 built: `server/` (Ktor, Postgres, Flyway, Cloudflare Access JWT check), Dockerfile, production compose, deploy/backup scripts, GitHub workflow, `docs/DEPLOYMENT.md`. Image built and run locally against Postgres (health, migrations, 401 without Access). Step 2 built: sync (`POST /api/sync`, `sync_rows` JSONB store, app `SyncClient` + `SyncWorker` + Sync screen), tested two-phone end to end in-process and the store against real Postgres. Server deployed and reachable through Access (2026-10-17). Step 3 built: LLM category suggestions (`POST /api/categorize`, Claude Haiku, cached per merchant in Postgres; the phone asks before each sync). Step 4 built: web dashboard (Overview, Ledger, Inbox, Rules) at `/`. The phone clears notifications for captures handled on the web and re-applies aliases pulled from it. Verified on the phone and server 2026-10-17: sync both ways, dashboard edits, live updates (category changes instant, new entries within seconds). Not yet tried: a wipe-and-reinstall restore. |
| Phase 5: statements | Built: RBC PDF statements (chequing and Visa) imported on the dashboard: read, checked against their totals, matched to captures, the rest added. Real statements read and add up. Not yet imported for real. |
| Design pass | App first (2026-10-17): one theme for app, widget and (next) dashboard. Warm paper and ink, one green for money in and progress; Geist with Geist Mono micro-labels (bundled, OFL, `app/licenses/`); big amounts with small cents; pill chips and buttons; cards. New navigation: a floating pill bar (Home, Activity, Inbox, More) with a round add button. New Home: month hero card (budget with an even-pace tick, or vs last month), scrubbable running-total chart against last month, 6-month and 12-month bars, inbox callout, top categories, recent. Inbox cards have Accept / Change buttons besides the swipes. Quick add: categories in a sideways-scrolling strip so the numpad always fits. Widget, launcher icon, notification icon and splash recolored. Theme in `ui/theme/`, shared pieces in `ui/components/`. Dashboard next, once the app is signed off. |

What's verified so far:
- `./gradlew :core:test`: 119 tests passing (budgets, trends, subscriptions, amount parsing, money formatting, export format, numpad entry, chip ranking, month summary, week spend, parsers, golden fixtures, merchant cleanup, dedupe, categorizer, rules engine, rules TOML, splits).
- `./gradlew :app:testDebugUnitTest`: 62 tests passing (budgets, settling owed parts, Room DAO, 60 s re-post dedupe, source stats, export round trip, extras dump, v1 to v5 migration, capture pipeline end to end, inbox, rules repository (TOML round trip, always-do-this, aliases renaming the past), tags, splits, seed data, quick-entry save and edit, delete and undo, category tree, category edits, deleting other apps' raw events).
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
| DB | Postgres 17 (own container), Flyway migrations, plain JDBC + HikariCP | Chosen over SQLite to match Vault/Mosaic ops (pg_dump backups, pg_isready). Exposed skipped for now (1.x renamed its API; the step 1 server needs only a health query) |
| Web UI | Server-rendered kotlinx.html + HTMX + Chart.js | One deployable, no JS build step. Swap to a SPA only if it starts hurting |
| Auth | Cloudflare Access app on the domain: browser via your Access login, phone via an Access service token. The server verifies the `Cf-Access-Jwt-Assertion` JWT (team keys, AUD) on everything but `/healthz` | Replaces the bearer token; also blocks requests that skip Cloudflare and hit the VPS directly |
| LLM | Anthropic API, `claude-haiku-5-5` | One call per unseen merchant, result cached |
| Deploy | `tally.ems-place.com`: Cloudflare -> VPS Caddy -> FRP -> mini-PC `127.0.0.1:18410`. Self-hosted runner `[minipc, tally, prod]`, `scripts/deploy.sh` (same shape as Vault's) | See `docs/DEPLOYMENT.md` |
| Backups | `scripts/backup-db.sh` (pg_dump, keeps 30) from cron, copied wherever Vault backups go | |

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
      normalize/                # MerchantNormalizer, Alias
      rules/                    # Categorizer, MerchantMemory (RulesEngine, TOML codec later)
      dedupe/                   # CaptureDeduper
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
        inbox/                  # InboxScreen, CategoryPickerSheet (always-do-this)
        rules/                  # RulesScreen, TomlEditorScreen, AliasesScreen
        transaction/            # TransactionScreen (detail, tags, splits)
        insights/               # StatsScreen, SubscriptionsScreen, OwedScreen
      quickentry/               # QuickEntryActivity, QuickEntrySheet, QuickEntryTileService, QuickAddShortcut
      widget/                   # QuickAddWidget (Glance) + receiver
      sync/                     # SyncTables, SyncClient, HttpSyncTransport, SyncSettings, SyncManager + SyncWorker
    src/main/res/xml/           # file_paths.xml (FileProvider), data_extraction_rules.xml
    src/test/kotlin/...         # Robolectric: RawEventRepositoryTest, ExtrasJsonTest
    schemas/                    # Room schema JSON, committed
  server/                       # Ktor: ServerConfig, AccessAuth, Database (Flyway), Application routes
    src/main/resources/db/migration/   # Flyway SQL
  Dockerfile, docker-compose.production.yml, .env.example
  scripts/                      # deploy.sh, backup-db.sh, restore-db.sh
  .github/workflows/deploy.yml  # tests on GitHub, deploy on the mini-PC runner
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

**Database v6 (built).** v6 adds `raw_events.updatedAt` (default 0; set on receipt and on every parse status change) so raw events sync. v5 adds `splits.settledAt` (owed portions paid back). v4 adds `aliases.priority` (first matching alias wins). v1 had `raw_events` only; v2 adds every table below through a Room `AutoMigration(1, 2)`; v3 adds `account_identifiers` (`AccountIdentifierEntity`: id, accountId, last4, updatedAt, deleted), several last-4s per account, learned when you set the account on a captured transaction. Tested in `TallyDatabaseMigrationTest`. No foreign key constraints: sync can deliver rows in any order, so references are plain indexed columns.

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

// Reports expand splits (core `withSplits`): categorized parts count in their category, the
// remainder in the transaction's, and owed parts don't count as spending.
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

**Done when:** you buy something with Samsung Pay and with the physical card, and both the RBC alert and the wallet notification appear in the raw events screen. **Checked with Google Pay + debit (2026-10-16); Samsung Pay and physical card still to see.** Setup has a "Delete" for raw events from other apps (what "Log all packages" collected).

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
- [ ] (Ongoing; 2 shapes so far, documented on each parser.) Read the fixtures and write down every distinct notification shape per package (purchase, refund, deposit, withdrawal, e-Transfer, payment to card, declined, promotional junk). Check whether group summaries duplicate the real notification and should be `Ignored`.
- [ ] (Started: chequing withdrawal.) `RbcParser`: one regex per shape. Extract amount, merchant (if present), card last4 or account name, and `kind`. Payments to your own credit card from chequing = `TRANSFER`. Declined and promo = `Ignored`.
- [ ] `SamsungWalletParser`: amount, merchant, card hint.
- [ ] (Started: purchase.) `GoogleWalletParser`: same. Turned out to matter most: with debit, it is the only source of the merchant name.
- [x] Amount parsing helper: handles `$1,234.56`, `CAD 4.25`, French formats (`4,25 $`, `1 234,56 $`). Done in Phase 0 as `Amounts`; extend its tests with real strings from fixtures.
- [x] **Golden tests:** (`GoldenFixturesTest`, `@TestFactory`; first fixture `2026-10-16-gwallet-debit-purchase`) each fixture file (`RawEventExport` format, read with `RawEventExportCodec` and `toRawNotification()`) has a sibling `.expected.json`. A test iterates all fixtures and asserts parser output. Adding a new case = drop two files in a folder.

**Normalization (in `core`)**
- [x] `MerchantNormalizer.normalize(raw)` (aliases are a constructor list; Phase 3 feeds them from the DB):
  1. Apply user aliases (regex, first match wins).
  2. Generic cleanup: strip trailing store numbers (`#1234`, ` 01234`), trailing city + province codes (`HAMILTON ON`), payment processor prefixes (`SQ *`, `TST* `, `PAYPAL *`), collapse whitespace, title-case.
- [x] Unit tests with real merchant strings from fixtures (plus common Canadian shapes; title-case also after hyphens: "7-Eleven").

**Dedupe (in `core`)**
- [x] `CaptureDeduper.findMatch`: candidate = existing non-manual transaction (deleted ones too, so an ignored purchase absorbs its second notification) with the same `amountCents`, `occurredAt` within 10 min, no conflicting account (accounts come from `account_identifiers`, not `walletSource`), not already merged with an event from the same source. Closest in time wins.
- [x] Merge policy (`CapturePipeline.merge`; earliest time wins): first event creates the transaction. Later events attach their raw event and fill in missing fields. Wallet merchant name replaces RBC's if RBC's looks truncated or uppercase-mangled. RBC event sets the account authoritatively.
- [x] Tests (`CaptureDeduperTest`, `CapturePipelineTest` on the real fixture strings): wallet then RBC, RBC then wallet, two genuine identical purchases 2 minutes apart (should NOT merge if both come from the same source).

**Categorization v1 (in `core`)**
- [x] `Categorizer`: v1 is merchant memory only; rules (Phase 3) go in front of it. Merchant memory (`MerchantEntity.lastCategoryId`) then none.
- [x] Confidence: memory with `confirmCount >= 2` = high (log silently). Memory with 1 = medium (notify). No match = low (notify + inbox).

**Android wiring**
- [x] Listener pipeline (`CapturePipeline`, serialized by a mutex, one DB transaction per capture): store raw, pick parser by package, parse, normalize, dedupe, categorize, write transaction with `status = CAPTURED`, mark raw event `PARSED` / `IGNORED` / `FAILED`.
- [x] `FAILED` from a watched package = an inbox flag (dismiss by swiping; dismissed ones are not re-parsed) ("couldn't parse a notification from RBC") with a link to the raw event. This is how you find out RBC changed their wording.
- [x] Re-parse action on the raw events screen (refresh icon): re-runs every watched event without a transaction (PENDING and FAILED), oldest first, without posting notifications. Idempotent thanks to `RawEventEntity.transactionId`.
- [x] **Capture notification** (`CaptureNotifier`, channel "Captured purchases", low importance; tagged by transaction id and updated when the second notification merges in). Split waits for splits (Phase 3). With no suggested category, tapping opens Change instead of confirming. "$4.25 Tim Hortons, Food/Coffee". Actions:
  - Change: opens `QuickEntryActivity` in edit mode.
  - Split: opens the transaction detail at the split section.
  - Ignore: broadcast receiver, soft-deletes the transaction.
  - Tapping the body confirms (status `CONFIRMED`, bumps merchant memory).
- [x] High-confidence captures post no notification at all; they land as `CONFIRMED` directly.
- [x] Merchant memory updates on every confirm or category change (`CaptureMemory`; once per decision, not per save). Account last-4s are learned the same way. Editing a capture never defaults its account to Cash.

**Done when:** a week of normal spending produces one transaction per real purchase (no duplicates from wallet + RBC), and you touched fewer than a third of them.

---

### Phase 3 (M3): Rules and inbox (1 to 2 weekends)

**Goal:** you control categorization fully, and cleanup is a weekly one-minute swipe session.

**Rules engine (in `core`)**
- [x] `RulesEngine.match(merchant, amount, account)`: rules sorted by `priority`, first match wins. Match = case-insensitive regex matching the **whole** normalized name (so "Uber" doesn't catch "Uber Eats"; write "Uber.*") AND optional amount range (inclusive) AND optional account. Rule matches are trusted (logged as confirmed) and apply the rule's tags.
- [x] Order in `Categorizer`: rules, then memory, then LLM (Phase 4), then none.
- [x] Regex compiled once per engine; invalid regex rejected at save time (`Rule.patternError`), and skipped rather than crashing at match time.

**TOML codec (in `core`)**
- [x] Schema (`RulesToml`; a hand-written parser for the TOML subset the file needs, so every error has a line number. Regexes are written as single-quoted literal strings so backslashes stay readable):
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
- [x] `export(document)` and `import(text, accountNames)`. Validation errors carry line numbers: unknown category, bad regex, negative amount, duplicate alias, unknown account, unknown key/section, min above max, commas in tags. All semantic errors are reported at once; syntax errors stop at the first.
- [x] Import is replace-all inside one DB transaction. Rule priority = order in the file. Categories are matched by path so existing ones keep their ids (and history); categories missing from the file are soft-deleted. Identical rules/aliases keep their ids. Aliases are re-applied to past purchases afterwards.
- [x] Round-trip test: export, import, export again; strings equal (core and against the DB).

**Android**
- [x] **"Always do this"** (inbox swipe-left picker and the detail screen's "Always do this…"; the new rule goes first): creates a rule from the normalized merchant + chosen category. Optional checkbox "only under $X" prefilled with the current amount rounded up. Asks once whether to re-categorize past transactions from that merchant.
- [x] **Rules screen** (menu > Rules): list in priority order, up/down to reorder (instead of drag), tap to edit in a form. Toolbar button "Edit as TOML".
- [x] **TOML editor screen:** monospace text field, Validate button showing errors inline by line, Save disabled until valid. Plain text editing is fine; it's for bulk changes.
- [x] **Alias manager:** list + "from a recent purchase" in the add dialog (prefills the raw merchant string as an escaped regex and the cleaned-up name). Saving renames past purchases' merchants.
- [x] **Tags:** add/remove on transaction detail; filter ledger by tag (chips above the list).
- [x] **Splits** (detail screen; also the capture notification's Split action): split a transaction into N parts with category each, or mark a portion `owedBy` a person (for "friend owes me" tracking; simple text name, no contacts integration).
- [x] **Review inbox** (bell icon on the ledger; tap = edit sheet for now):
  - Contents: `CAPTURED` low/medium confidence transactions, parse failures, (Phase 5) unmatched statement rows and stale unmatched transactions.
  - Card UI: merchant, amount, suggested category. Swipe right = accept, swipe left = category picker, tap = detail.
  - Badge count on the widget and in the app.

**Done when:** you can define all your categorization in rules + aliases, edit them on the phone in either form or TOML, and the inbox empties in under a minute a week.

---

### Phase 4 (M4): Server, sync, web, LLM (2 to 3 weekends)

**Goal:** data backed up off-phone, big-screen dashboard and rule editing, LLM handles unknown merchants.

**Server**
- [x] `server` module: Ktor 3.6 + Postgres (HikariCP, Flyway on startup), depends on `core`. `/healthz` (open), `/api/health` (DB), `/api/me` (who Access says you are), `/` placeholder page.
- [x] One generic table instead of mirrors: `sync_rows (table_name, id, updated_at, deleted, data JSONB, server_seq)` (migration V2). `data` is the Room entity as JSON (every column, defaults included), so a new phone column needs no server migration; the dashboard reads the JSON. Raw events sync too, bank and wallet packages only (never "log all packages" notifications).
- [x] Auth: Cloudflare Access JWT checked by Ktor's JWT auth against the team's certs (`AccessAuthTest` signs real RS256 tokens: valid user, service token, wrong audience, foreign signature, expired). Phone: service token id/secret in the app's settings (step 2).
- [x] Dockerfile (Gradle on temurin 25 with `TALLY_SKIP_ANDROID=true`, then temurin 21 JRE alpine, non-root). Postgres volume `tally_postgres`. `.gitattributes` forces LF on `gradlew` and `*.sh` (CRLF broke the image build on Windows checkouts).
- [x] Nightly backup job: script + cron line in `docs/DEPLOYMENT.md` (cron itself is set up on the mini-PC).

**Sync protocol** (single user, simple on purpose)
- [x] `POST /api/sync` with `{ deviceId, since: cursor, changes: [ {table, id, updatedAt, deleted, data} ] }` (`core/sync/SyncProtocol`). `transaction_tags` rows use `transactionId|tagId` as id.
- [x] Server (`core/sync/SyncServer`, shared by Postgres and the in-memory test store): applies a row only if `incoming.updatedAt > stored.updatedAt` (one upsert with a `WHERE`), gives every write the next `server_seq`, returns rows after `since` in pages of 500 plus the cursor, and sends back its own copy of any row you pushed an older version of. Requests are handled one at a time so sequence numbers commit in order. Sequence numbers can have gaps (skipped upserts still draw one); only their order matters.
- [x] Client (`app/sync/SyncClient`): no dirty flags. A watermark (start time of the last good sync) picks the rows to push (`updatedAt >= watermark - 60 s`; first sync pushes everything), in batches of 200; then pulls pages until done. Incoming rows are stored only if newer than the local copy. The cursor is saved after each page is stored, so an interrupted sync just resumes.
- [x] When: `SyncWorker` (WorkManager, needs a network) hourly, on app open, and 30 s after the last local change in a burst (Room invalidation, raw events excluded). Nothing runs until the Sync screen has the server URL and service token. Changing the server URL starts over.
- [x] Clock-skew guard: the server refuses `updatedAt` more than 5 min in the future; the Sync screen reports refused rows.
- [x] Tests: `SyncServerTest` (core), `PostgresSyncStoreTest` (runs when `TALLY_TEST_DATABASE_URL` is set; CI has a Postgres service), `/api/sync` auth + bad JSON in `AccessAuthTest`, and `SyncClientTest` (Robolectric, two phones, JSON on the wire): a fresh phone restores everything (budget edit beats the fresh seed, tags, splits, captured merchant, raw events) and never receives a WhatsApp notification; offline edits on both phones (later wins); delete after edit; a quiet second sync sends and receives nothing.
- [x] Phone side: `HttpSyncTransport` sends `CF-Access-Client-Id` / `CF-Access-Client-Secret`, never follows redirects (a bad token is redirected to the Access login page), and treats 401/403/3xx or non-JSON as "Access refused the token". Sync screen (menu, Sync): URL (default `https://tally.ems-place.com`), client id, secret, Test connection (`/api/me`), Sync now, last result.

**LLM categorization**
- [x] Server endpoint `POST /api/categorize { merchant, amountCents, categories[] }` -> `{ category, cached }` (`core/llm`). Off (503) without `ANTHROPIC_API_KEY`; model `claude-haiku-5-5` (`ANTHROPIC_MODEL`), at most `LLM_DAILY_LIMIT` (200) model calls a day (429 after). `AnthropicModel` calls the Messages API over plain HTTP (no SDK).
- [x] Prompt (`CategoryPrompt`): Toronto context, the category paths, the normalized merchant and amount; exactly one path or `UNKNOWN`. Anything not exactly (case-insensitively) one of the paths counts as `UNKNOWN`.
- [x] Cache by merchant key (case and spacing ignored) in `llm_categories` (migration V3). A cached category is reused while it's still one of your categories; a cached `UNKNOWN` until the category list changes (hash). Only the merchant name, amount and category names leave for Anthropic.
- [x] Phone (`CategorySuggester`, run before each sync): captured expenses from the last 30 days with a merchant and no category. The answer enters as `categorySource = LLM`, status stays CAPTURED (inbox, notification says "(suggested)"; a notification is refreshed only if still showing). A conditional update never overwrites a category you picked meanwhile. Confirming teaches merchant memory, so that merchant is never asked again. Errors never fail the sync. Tested: `CategoryPromptTest`, `CategorizeServiceTest`, `AnthropicModelTest` (local stand-in server), route in `AccessAuthTest`, `PostgresCategoryCacheTest`, `CategorySuggesterTest`.

**Web dashboard** (kotlinx.html + HTMX + Chart.js)
- [x] Reads the synced rows (`sync_rows`) per request into a `Snapshot` (`server/web`), with row types that mirror the Room entities field for field (`RowsSchemaTest` checks them against the newest committed Room schema). Edits go through `SyncServer.write` (same lock, last write wins) and keep JSON columns the server doesn't know; the phone gets them on its next sync.
- [x] Ledger table with filters (date range, category incl. subcategories, tag, account, text search), inline category edit (HTMX; confirms and teaches merchant memory like the phone).
- [x] Monthly overview: cards (spent, income, budget left, to review), spend by category with budget marks and subcategories, day-by-day cumulative vs. last month (Chart.js line), last 6 months spent vs. income (bar), top merchants.
- [x] Rules page (rules ergonomics, 2026-10-17): rules, aliases and categories edited one row at a time (`RulesEdits`, `RulesPage`), each row saved on its own so phone and dashboard edits don't overwrite each other; a row with an error comes back with what you typed. Rules show how many past transactions they match; aliases re-file past merchants on save (server side, as the phone does after pulling). "Try it": bank text and amount in, the cleaned name, the alias and the rule (or memory) that would file it out. "Apply to existing": `RuleReplay` (core) runs the rules over the inbox and uncategorized transactions, never ones you filed and never transfers; a preview first, then apply (also on the phone's Rules screen). The TOML file stays under Advanced (saving it also re-files merchants).
- [x] Rule suggestions (Rules page, `RuleSuggestionRules` in core, `RuleSuggester` on the server), nothing saved until you tick it:
  - From your history: a merchant filed under the same category 3+ times (at most one odd one out in ten) that no rule covers.
  - From Claude (`ANTHROPIC_RULES_MODEL`, default `claude-sonnet-5-5`, max 20 calls a day): it gets merchant names, a few bank texts each, how you filed them, your categories and existing aliases and rules (no amounts), and answers with aliases and rules as JSON. Each one is checked: patterns compile, aliases match some bank text and change its name, rules use a known category, match some merchant (as named after the suggested aliases) and aren't already covered. What fails is listed with the reason.
  - Each suggestion shows its effect ("renames 14 transactions", "matches 14, 9 waiting to be filed"). Adding saves aliases first (re-filing merchants), then rules at the end of the list, refreshes the editors, and points at Apply to existing.
- [x] New merchants get ids from their name (`MerchantIds`), so the phone and the server re-applying the same alias create one "DoorDash", not two. Category naming and nesting rules live in core (`CategoryRules`) for both.
- [x] Dashboard edits are stamped after the row they read (`RowType.encode`), so a second edit within the same millisecond, or over a row from a phone clock slightly ahead, still wins.
- [x] Inbox page: confirm a suggestion, pick a category, or ignore.
- [x] Every POST needs `HX-Request: true` (a cross-site form can't send it), so the Access cookie can't be used for cross-site edits. HTMX 2.0.4 and Chart.js 4.4.7 from jsDelivr.
- [x] Live sync. `SyncServer` announces every request that stored rows (not echoes) as a `SyncEvent(cursor, source)`; `GET /api/events` streams them as Server-Sent Events (ping every 25 s for Cloudflare's idle limit). Dashboard pages reload on another device's change (a banner instead while you're typing, and on Rules); each page sends its own id (`X-Tally-Source`) with its edits so it skips its own echo. The phone follows the stream while Tally is on screen (`SyncManager.followServer`, reconnects with backoff) and syncs on others' changes; local changes sync 3 s after a burst. Not in the background: an always-open connection would keep the radio awake (would need FCM).
- [x] Manual entries can name a merchant (quick entry, cart button): suggestions from known merchants (most confirmed first), picking one preselects its usual category, the typed name goes through aliases like a captured one, and saving teaches merchant memory. Captures keep the merchant their notification named.
- [x] Phone: `SyncResult.pulledTables` says which tables got newer rows. After pulling transactions, capture notifications for ones no longer CAPTURED are cleared (`CaptureNotifier.dismissHandled`); after pulling aliases, `RulesRepository.reapplyAliases` re-files old transactions. Not yet built or run (`SyncClientTest` asserts `pulledTables`).

**Optional in this phase:** Gmail parsers for itemized receipts (Gmail API with OAuth in "testing" mode, your account as the only test user). Only worth it if you find yourself wanting order-level detail.

**Done when:** you can wipe and reinstall the app, sync, and get everything back; and you can do a monthly review entirely on the web.

---

### Phase 5 (M5): Statement reconciliation (built 2026-10-17)

**Goal:** a monthly sanity check that catches anything the alerts missed and corrects pending amounts.

RBC's monthly **PDF** statements are the input (they arrive anyway; no CSV download step). Reading happens on the server, so the import lives on the dashboard; the phone gets the result through sync.

- [x] `RbcStatements` in `core` reads the text PDFBox extracts (sorted by position) from both layouts:
  - Chequing: rows span lines (type line, merchant line with amount and maybe a balance), dates carry over, page breaks repeat the letterhead. The text doesn't say which column an amount was in, so the signs come from the running balance: within each run of rows ending in a printed balance, the withdrawal/deposit split that adds up, ties broken by the description ("refund", "Autodeposit"...). Checked against the statement's total deposits and withdrawals.
  - Credit card: one line per row (`MMM DD MMM DD description $amount`, sidebar text after it), reference and foreign-currency lines skipped, years worked out across New Year. Checked against "Purchases & debits" and "Payments & credits".
  - Kinds: purchases EXPENSE; refunds and corrections negative EXPENSE; deposits INCOME; card payments TRANSFER.
  - A statement that doesn't add up still imports, with the problems shown in the preview.
- [x] `StatementMatcher` (`core`): same amount (or within 25% when the merchant clearly agrees: tips, holds), date within 5 days before to 1 day after the statement's date (chequing dates are posting dates), merchant similarity as the tiebreak (shared words, or a word inside squashed bank text: "DOORDASHWENDYS" has "wendys"). Best pairs first, one to one.
- [x] Import (`server/web/StatementImport`, dashboard Statements page): upload, preview, import.
  - Account: from a last-4 the phone learned (`account_identifiers`), else by name (Visa/credit vs chequing), else you pick.
  - Matched: takes the statement's amount; CONFIRMED becomes RECONCILED (captures still in the inbox stay CAPTURED); an unknown account is filled in.
  - New rows: `source = CSV`, dated noon on the statement date, merchant through your aliases, categorized by rules then merchant memory (trusted or rule: confirmed; otherwise to the inbox). With an LLM configured, suggestions for the rest arrive in the background after the import. Income and transfers are confirmed with the description as the note.
  - Missing: transactions on that account in the period the statement doesn't have are listed (not changed).
  - Ids of new rows come from the statement row (kind, last 4, date, amount, description, occurrence), so importing the same statement again adds nothing.
- [x] Tests: made-up statements in the real layouts (`RbcStatementsTest`: page breaks, sign solving, New Year, refunds, corrections), `StatementMatcherTest`, `StatementImportTest` (preview, import, re-import, upload route). `RealStatementsTest` reads real PDFs from `TALLY_STATEMENTS_DIR` (never committed) and checks they add up: the first two (chequing 34 rows, Visa 30 rows) do.
- [x] Money to and from people (e-Transfers): the sender is the merchant (reference code stripped), and it always goes to the inbox. Rules with `ask = true` (TOML; `rules.ask`, schema v7) are defaults you accept with one tap; merchant memory of a person suggests but never files (`Categorizer(trustMemory = false)`); a plain rule still files automatically (for income that's always the same: "Canada" -> Income/Government, payroll).
- [x] What money in means comes from the category (`CategoryKinds`): under the top-level category named "Income" it's INCOME; under a spending category it's money back (a negative EXPENSE lowering that category, like a refund). Set wherever you pick a category (phone, dashboard, import).
- [x] Owed to me: money in whose sender and amount match an open owed part (`OwedMatch`: same amount, first name or full name agrees) is offered as "Settles Danya's $31.00" in both inboxes (swipe right on the phone, Settle on the web): the part is settled and the money becomes a TRANSFER (the owed part never counted as spending).
- [ ] Not done: refunds aren't linked to their purchase (they net out by merchant); RBC e-Transfer notifications aren't parsed yet (need an export).

**Done when:** importing a month's statement produces zero surprises you can't explain from the inbox.

---

### Phase 6 (M6): Budgets and insights (1 weekend, then ongoing)

- [x] Monthly budget per category (Categories > tap a category). Total = per top-level category its own budget, else the sum of its subcategories' (`core/report/Budgets`), so they never double count. Weekly = total x 12 / 52.
- [x] Widget progress bar: week spend vs. budget, color shift past 80% (tertiary) and 100% (error). The month view uses the same thresholds (`BudgetStatus`) per category and overall.
- [x] On-device stats screen (menu > Stats): 6-month column chart, net per month, top merchants over 30 days. Category breakdown stays in the month view. Drawn with plain Compose instead of Vico (a handful of bars didn't justify the dependency).
- [x] Income vs. spend, net per month (Stats). Income only appears once a deposit parser exists (needs a deposit notification fixture).
- [x] Recurring detection (`Trends.recurring`): same merchant, 2+ charges, every gap 25 to 35 days (monthly) or 350 to 380 (yearly), amounts within 10% of the median, last charge within two periods. Menu > Subscriptions lists them with yearly cost and next expected date.
- [x] "Owed to me" (menu): outstanding parts grouped by person, Settled / Undo, recently settled list. Owed parts never count as spending.

---

## 8. Testing strategy

- **`core` carries the weight.** Parsers, normalizer, dedupe, rules, TOML codec, and matcher are all pure functions with JUnit Jupiter tests (`./gradlew :core:test`). Target: every bug you ever hit becomes a fixture. Today: `AmountsTest`, `MoneyTest`, `RawEventExportTest`, `AmountEntryTest`, `CategoryRankingTest`, `MonthSummaryTest`, `WeekSpendTest`, `ParsersTest`, `GoldenFixturesTest`, `MerchantNormalizerTest`, `CaptureDeduperTest`, `CategorizerTest`, `RulesEngineTest`, `RulesTomlTest`, `SplitsTest`, `BudgetsAndTrendsTest` (119 tests).
- **App JVM tests on Robolectric** (`./gradlew :app:testDebugUnitTest`) for Room DAOs, repositories and Android-type helpers, with an in-memory Room DB. Today: `RawEventRepositoryTest`, `ExtrasJsonTest`, `DefaultDataTest`, `TallyDatabaseMigrationTest`, `LedgerRepositoryTest`, `CategoryTreeTest`, `CategoryRepositoryTest`, `CapturePipelineTest`, `RulesRepositoryTest` (50 tests). Use Room's `useWriterConnection { it.immediateTransaction { } }` for transactions, not `withTransaction`: the latter fails under the driver-based migration helper. Robolectric needs the `--add-opens` flag already set in `app/build.gradle.kts`.
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
- [ ] RBC alert wording for each event type. Known so far: chequing withdrawal, "A withdrawal of $34.32 was made from your RBC account ********8805 on 10/17." **The date is UTC** (purchase was 9:04 PM Oct 16 Toronto), so always use the post time. Still to see: credit card purchase, deposit, e-Transfer, payment to card.
- [ ] Whether wallets post group-summary notifications that duplicate the real one. Google Wallet: no summary for a single purchase (channel `tapandpay.transactions.low`). Samsung Wallet: not seen yet.
- [ ] Whether RBC's CSV has a stable transaction ID (Phase 5).
- [ ] Whether chequing alerts distinguish debit purchases, e-Transfers, bill payments, and credit card payments clearly enough to set `kind` automatically, or whether that needs rules. So far: a debit purchase is a generic "withdrawal" with no merchant, so it can't be told apart from other withdrawals by the RBC text alone.
- [x] **Accounts need several identifiers.** Done in schema v3 (`account_identifiers`). A Google Pay debit purchase shows the card's last 4 (••3881) in the wallet and the chequing account's last 4 (********8805) in the RBC alert. Account matching (dedupe, Phase 2) needs a list of last-4s per account, not one `last4` column (schema v3).
