# Remote Config support — design

Date: 2026-10-07
Branch: `feature/remote-config`

## Goal

Make Firebase Remote Config usable on the JVM through this SDK. The Android
`firebase-config` 21.6.0 and `firebase-installations` 17.2.0 libraries already
ship in the jar (see `build.gradle.kts`), but the README marks both as not
functional. This work fills the Android shims they depend on so the unmodified
Android code runs.

Success means `Firebase.remoteConfig(app)` supports `setDefaultsAsync(Map)`,
`fetch`, `activate`, `fetchAndActivate`, `get*` values, `getAll`,
`getKeysByPrefix`, `getInfo`, `setConfigSettingsAsync`, and `reset` against the
live `fir-java-sdk` project. The same behaviour lets the JVM target of
[firebase-kotlin-sdk](https://github.com/GitLiveApp/firebase-kotlin-sdk) pass its
`firebase-config` common tests.

## Scope

In scope: the APIs listed above.

Out of scope:

- Realtime updates (`addOnConfigUpdateListener`).
- `setDefaultsAsync(@XmlRes int)`, which needs Android XML resources.
- Real `PackageManager.getPackageInfo` data. Both libraries catch the
  `NameNotFoundException` it throws today.

## Approach

Fill the shims; do not reimplement Remote Config. Firestore, Database,
Functions, and Storage already work this way. A REST-based rewrite like Auth's
would duplicate code Google ships and drift from it. Auth took that route only
because its Android source is closed.

The Admin SDK ([firebase-admin-java](https://github.com/firebase/firebase-admin-java))
is not a reference for this work. It manages templates
(`/v1/projects/{id}/remoteConfig`) and evaluates server templates
(`namespaces/firebase-server/serverRemoteConfig`) with service-account OAuth.
Client fetch uses `namespaces/firebase:fetch` with the API key and a Firebase
Installations token, which the bundled Android library already implements.

## Gaps found

Found by scanning the extracted jars with `javap` for Android API calls and
comparing against `src/main/java/android/**`:

1. `PackageManager.getServiceInfo` does not list `RemoteConfigRegistrar` or
   `AbtRegistrar`, so component discovery cannot create Remote Config.
2. `Context` lacks `getFilesDir`, `openFileInput`, `openFileOutput`, and
   `deleteFile`. Remote Config stores fetched, activated, and default configs as
   files; Installations stores `PersistedInstallation.<key>.json`.
3. `Context.getSharedPreferences` returns one object that accepts only
   whitelisted keys and throws `IllegalArgumentException` otherwise. Remote
   Config uses `frc_<appId>_firebase_settings`; Installations reads
   `com.google.android.gms.appid`.
4. The `SharedPreferences` interface lacks `getInt`, `Editor.putInt`, and
   `Editor.clear`, all called by Remote Config.
5. Missing classes or members: `android.util.JsonReader`,
   `android.net.TrafficStats`, `android.text.format.DateUtils.formatElapsedTime`,
   `android.content.res.Configuration` (with `locale`), and
   `Resources.getConfiguration()`.

## Components

| Change | File | Purpose |
|---|---|---|
| Register `com.google.firebase.remoteconfig.RemoteConfigRegistrar` and `com.google.firebase.abt.component.AbtRegistrar` | `android/content/pm/PackageManager.java` | Component discovery |
| `open fun getFilesDir(): File`, default `File(java.io.tmpdir, "firebase-files")` | `com/google/firebase/FirebasePlatform.kt` | User-configurable location for persisted files; non-breaking |
| `filesDir`, `openFileInput`, `openFileOutput`, `deleteFile` | `android/content/Context.kt` | Resolve names inside `FirebasePlatform.getFilesDir()` |
| `getInt`, `Editor.putInt`, `Editor.clear` | `android/content/SharedPreferences.java` | Interface methods Remote Config calls |
| Platform-backed preferences for files named `frc_*` and `com.google.android.gms.appid` | `android/content/PlatformSharedPreferences.kt` | Store each key as `"<file>\|<key>"` via `FirebasePlatform.store/retrieve/clear`; `clear()` removes only that file's keys |
| Route those two file names to `PlatformSharedPreferences` | `android/content/Context.kt` | All other files keep the strict whitelist, which also gains throwing `getInt`/`putInt`/`clear` |
| `JsonReader` and its `JsonToken`/`JsonScope` support | `android/util/` | Port from AOSP (Apache 2.0), like the other ported shims |
| `TrafficStats` with no-op `setThreadStatsTag`/`clearThreadStatsTag` | `android/net/TrafficStats.java` | Installations tags its network thread |
| `DateUtils.formatElapsedTime(long)` | `android/text/format/DateUtils.java` | Throttling messages in `ConfigFetchHandler` |
| `Configuration` with `locale = Locale.getDefault()`; `Resources.getConfiguration()` | `android/content/res/` | Locale sent in the fetch request |
| Remove strikethrough and footnote 2 for Remote Config and Installations; document `getFilesDir` beside `getDatabasePath` | `README.md` | User docs |

`clear()` must enumerate a file's keys, but `FirebasePlatform` exposes no key
listing. `PlatformSharedPreferences` therefore stores the set of keys it wrote
under `"<file>|__keys"` and clears each one.

## Data flow: `fetchAndActivate()`

1. `RemoteConfigRegistrar` builds the component. Fetch metadata lives in
   `frc_<appId>_firebase_settings` (platform-backed preferences).
2. Installations reads `com.google.android.gms.appid` (empty on the JVM),
   generates a FID, registers it with `firebaseinstallations.googleapis.com`,
   parses the response with `JsonReader`, and writes
   `PersistedInstallation.<key>.json` to `getFilesDir()`.
3. `ConfigFetchHttpClient` posts to `firebaseremoteconfig.googleapis.com`
   with the FID, its auth token, and the default locale.
4. Remote Config writes the fetched, activated, and default configs to
   `getFilesDir()` through `openFileOutput` and reads them with
   `openFileInput`.

## Error handling

No new error handling. Failures reach callers as they do on Android:
`FirebaseRemoteConfigFetchException` or `FirebaseInstallationsException` on the
returned `Task`. Preference files outside the two platform-backed ones still
throw on unknown keys, so new usages fail loudly. If a runtime gap appears that
the bytecode scan missed, work stops and the gap is reported before any fix.

## Testing

TDD: each test is written and seen failing for the expected reason before its
shim exists.

`src/test/kotlin/FirebaseRemoteConfigTest.kt` extends `FirebaseTest` and ports
the firebase-kotlin-sdk common tests
(`firebase-config/src/commonTest/.../FirebaseRemoteConfig.kt`) one-to-one onto
the Android API, keeping names, keys, values, and assertions:

| Test | Android API used |
|---|---|
| `testGettingValues` | `setDefaultsAsync(map)`, `getBoolean`/`getDouble`/`getLong`/`getString`, `getValue(..).source == VALUE_SOURCE_DEFAULT`, `asByteArray()` |
| `testNamedApp` | Second app `"named"` with a different application ID; its defaults stay out of the default app's `all` |
| `testGetAll` | `all[..]?.asBoolean()` etc. |
| `testGetKeysByPrefix` | Four `test_default_*` keys |
| `testGetInfo` | `fetchTimeMillis == -1`, `lastFetchStatus == LAST_FETCH_STATUS_NO_FETCH_YET`, default settings |
| `testSetConfigSettings` | 42 s timeout and minimum interval round-trip through `info.configSettings` |
| `testFetch`, `testFetchAndActivate` | `@Ignore`, as upstream: they need `test_remote_string = "Hello from remote!"` published in the console |

Teardown calls `reset().await()` before `FirebaseTest` clears the apps.

One added live test, `fetchAndActivate succeeds`, asserts
`info.lastFetchStatus == LAST_FETCH_STATUS_SUCCESS` against `fir-java-sdk`. It
exercises the Installations and fetch network path without console changes. It
does not prove remote values are applied; the ignored tests cover that once a
parameter is published.

Shim unit tests:

- `PlatformSharedPreferences`: round-trip of string, int, and long; isolation
  between two files; `clear()` removes only its own file's keys.
- `Context` file APIs: write, read, and delete inside a temporary
  `getFilesDir()`.
- `JsonReader`: parses an Installations-shaped response.

Gradle runs with `JAVA_HOME` set to JDK 17; Kotlin 2.0.20 fails to parse the
JDK 25 version string. The full suite must pass with clean output.

## Possible follow-up

Publishing `test_remote_string` would let the two ignored fetch tests run. A
fixture could do this with the Admin SDK, but it needs service-account
credentials in the test environment, so it stays out of this work.

## Changes agreed during implementation

The live fetch test surfaced two issues the bytecode scan missed. Angelos
decided both on 2026-10-07:

1. **Heartbeat storage.** `firebase-common`'s `HeartBeatInfoStorage` uses
   `getStringSet`, `putStringSet`, `remove`, and `getAll` (checking values with
   `instanceof Set`) on the `FirebaseHeartBeat<persistenceKey>` preference
   file. The strict whitelist supports none of these, so heartbeats failed for
   every product; Installations logged "Failed to get heartbeats header". These
   files become platform-backed too. `PlatformSharedPreferences` gains string
   sets and `remove`, and its key index records which keys hold string sets so
   `getAll()` returns them as `Set<String>`. `SharedPreferences.getAll()` takes
   Android's `Map<String, ?>` signature.
2. **Expected warnings in test output.** Each fetch logs a
   `NameNotFoundException` (from `getPackageInfo`), and each activate logs
   `AbtException: The Analytics SDK is not available`. Both are expected on the
   JVM. `FirebaseTest` routes platform logs through an overridable `log`, and
   `FirebaseRemoteConfigTest` captures them and asserts the live fetch logs
   exactly these expected messages.
