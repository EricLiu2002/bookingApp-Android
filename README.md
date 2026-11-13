# BookingApp for Android

A standalone, offline Android app for the BookingApp family ledger. It keeps its own SQLite
ledger on the phone, so costs and earnings can be recorded anywhere, with no network and no
server. It applies the same rules as the PC version: validation, exact money, monthly and
yearly summaries, CSV import with the Chinese category aliases, and duplicate flagging.

The PC version (Spring Boot backend + React web app) lives in the **bookingApp** repository
(`C:/NewSkillsPratice/spring/bookingApp`). This repository does not change it and never
calls it.

## Prerequisites

- **Android Studio** (2025.2 or newer) with the Android SDK, platform 36 and build tools.
- **JDK 21: Android Studio's bundled JBR.** The backend's JDK 25 is not used for Android
  builds. Point Gradle at the JBR in one of these ways:
  - in Android Studio: *Settings → Build Tools → Gradle → Gradle JDK → jbr-21*;
  - on the command line, set `JAVA_HOME` for the shell, for example in Git Bash:
    `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`
    (PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`).

  `gradle.properties` deliberately does not hard-code a JDK path.
- `local.properties` with `sdk.dir` (Android Studio writes it on first sync; on Windows the
  colon must be escaped: `sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk`).

## Build, install and run

```bash
./gradlew assembleDebug      # build app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # install on a connected phone or running emulator
```

Or open the folder in Android Studio and press *Run*. The app opens on the Dashboard.

### Display currency

Amounts are shown in `USD` unless the app is built with another ISO 4217 code, like the web
app's `VITE_CURRENCY`. Set it in `gradle.properties` (`bookingapp.currency=CAD`) or per build:

```bash
./gradlew installDebug -Pbookingapp.currency=CAD
```

## Tests

```bash
./gradlew testDebugUnitTest   # all JVM tests, including the Compose UI tests
./gradlew lint
```

- **Domain and storage tests** run on the plain JVM. Their cases are ported from the
  backend and frontend tests in `reference/`, and `SchemaParityTest` checks that the app's
  schema is exactly the backend's Flyway V1 + V2.
- **UI, navigation, Android SQLite and ledger-reload tests** run on the JVM with
  Robolectric, so no emulator is needed. The first run downloads Robolectric's Android
  runtime jar into `~/.m2/repository`.
- `./gradlew connectedDebugAndroidTest` runs on an emulator or device; there are currently
  no instrumented-only tests.

## Using the app

- **Dashboard:** this month's and this year's Income, Outcome and Balance, with pie charts
  for costs (grouped by prefix, such as `FO`) or earnings.
- **Transactions:** a month's list with a Costs / Earnings / All filter, a sort menu, edit
  on tap, and delete with confirmation.
- **Add:** one form for costs and earnings. After a save it stays open for the next entry.
- **Import:** pick a `.csv` or `.txt` file (UTF-8 or GB18030, at most 2 MB and 5,000 rows),
  check the preview, then import all selected rows or none.

### Offline only, and backup

- The app has **no `INTERNET` permission** and no sign-in. It relies on the phone's screen
  lock. The ledger never leaves the phone through the app itself.
- **Android Auto Backup** is on and includes only the ledger database (`bookingapp.db`).
  Until there is an export, that backup and the PC copy are the protection against losing
  the phone.

## Loading the PC ledger onto the phone

The phone ledger can be **replaced** (not merged) by the backend's `bookingapp.db`:

1. **Stop the backend**, so the file is complete. Make a backup copy as described in the
   bookingApp README.
2. **Copy the file to the phone**, for example over USB into *Downloads*, or with
   `adb push %USERPROFILE%\.bookingapp\bookingapp.db /sdcard/Download/`.
   Copy only `bookingapp.db`; leave any `-journal` file behind.
3. In the app, open **Import → Replace ledger with a database file → Choose database file**
   and pick it. The app checks the file: that it is SQLite, passes SQLite's integrity and
   foreign-key checks, has exactly the backend's ledger tables, and comes from a backend
   migration this app supports (Flyway V2 or older). A file that fails is rejected with the
   reason, and the phone is unchanged.
4. Confirm the dialog, which shows the file's transaction count, category count and date
   range. The app opens the Dashboard with the file's data.
5. **Restore previous ledger** (on the Import screen) puts back the ledger that the last
   reload replaced, then deletes that backup. Only one backup is kept.

## Project structure

```
app/src/main/java/com/bookingapp/
  domain/            pure Kotlin rules: Money, TransactionValidator, ChartGroups,
                     importing/ (CsvReader, CategoryAliases, ImportRowParser,
                     ImportPreviewBuilder, DuplicateFlagger, CsvDecoder)
  data/              DatabaseFactory, repositories, LedgerSchema,
                     reload/ (LedgerFileValidator, LedgerReplacer)
  ui/                Compose screens and ViewModels: dashboard/, transactions/, edit/,
                     importing/, navigation/, common/, theme/
  AppContainer.kt    manual dependency wiring; reopens the ledger after a reload
app/src/main/sqldelight/.../Ledger.sq   schema (verbatim from Flyway V1/V2) and queries
app/src/test/                           JVM and Robolectric tests, fixtures
reference/backend/                      copied from bookingApp: Flyway SQL, CSV import code and tests
reference/frontend/                     copied from bookingApp: chart, money, decodeCsv helpers and tests
openspec/                               specs and changes; specs copied from bookingApp are reference only
```

### Keeping `reference/` in sync with bookingApp

`reference/` is not compiled. Tests read the SQL from it, and the Kotlin ports follow its code
and tests. When bookingApp changes its schema or rules, copy the files again:

| bookingApp file | here |
| --- | --- |
| `backend/src/main/resources/db/migration/V*.sql` | `reference/backend/` |
| `backend/src/main/java/com/bookingapp/importing/{CsvReader,ImportRowParser,CategoryAliases,ImportService}.java` | `reference/backend/` |
| `backend/src/test/java/com/bookingapp/importing/*Test.java` | `reference/backend/` |
| `frontend/src/**/{chart,money,decodeCsv}{,.test}.ts` | `reference/frontend/` |

A new backend migration (V3) also needs a matching SQLDelight `.sqm` migration and an increase
of `LedgerSchema.SUPPORTED_FLYWAY_VERSION`; `SchemaParityTest` fails until the schemas match.

## Next steps

- **Export the phone ledger back to the PC** — the obvious follow-up; today the transfer is
  one-way (PC → phone).
- Merging ledgers, syncing, category management and iOS are out of scope for now.
