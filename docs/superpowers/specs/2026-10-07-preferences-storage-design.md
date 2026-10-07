# Preferences stored as files — design

Date: 2026-10-07
Branch: `feature/remote-config` (PR #70)

## Goal

Fix four findings from the `/code-review xhigh` of PR #70:

1. **Remote Config stuck on defaults.** Remote Config keeps its fetch
   metadata, including the ETag, in preferences stored through
   `FirebasePlatform`, and its config JSON in `getFilesDir()`, which defaults
   to the temp folder. When the OS cleans the temp folder, the next fetch
   sends the stale ETag, the server answers `NO_CHANGE`, nothing is cached,
   and the app serves defaults until the template changes.
2. **Flaky tests.** The test stores are plain maps that background threads now
   write to, and logs from work that outlives a test can fail the next one.
3. **AOSP ports lack the header comment** that every new file needs.
4. **Firebase-internal preferences burden `FirebasePlatform` implementers**
   with long keys and background writes, and preference files are routed by
   hard-coded name prefixes on top of a strict whitelist.

## Approach

Store each preferences file as one JSON file under
`getFilesDir()/shared_prefs/`, as Android does. Preferences, Remote Config's
config files and the Installations file then share one folder. They are kept
or lost together, so they cannot drift apart (finding 1). Firebase-internal
keys never reach `FirebasePlatform` (finding 4). Every preferences name
uses this storage, and the strict whitelist is deleted.

Angelos approved replacing `PlatformSharedPreferences` (written on this
branch, unreleased) and deleting the strict whitelist.

Auth is unaffected: it writes the signed-in user directly with
`FirebasePlatform.store` (`FirebaseAuth.kt`), not through preferences.

## Components

| Change | File | Purpose |
|---|---|---|
| `PreferencesFile` replaces `PlatformSharedPreferences` | `src/main/java/android/content/PreferencesFile.kt` | One instance per file path, shared through a `ConcurrentHashMap`. Loads `getFilesDir()/shared_prefs/<URL-encoded name>.json` on first use, keeps the values in memory, and guards them with a per-file lock. |
| Typed values | same | The JSON records each value's type (string, int, long, boolean, string set), so `getAll()` returns the stored types. |
| Batched `Editor` | same | Edits collect until `commit()` or `apply()`. A requested `clear()` applies first, then puts and removes, as on Android. The file is written to a temp file in the same folder and moved over the target with `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`. `apply()` writes synchronously. |
| Missing or corrupt file | same | A missing file reads as empty. A corrupt file is logged and read as empty; the next commit overwrites it. |
| `getSharedPreferences` | `src/main/java/android/content/Context.kt` | Returns the `PreferencesFile` for every name. The strict whitelist object is deleted, including its unreachable `FIREBASE_USER`, `fire-global`, `last-used-date`, and `\|T\|` branches. |
| `getBoolean` / `putBoolean` | `src/main/java/android/content/SharedPreferences.java` | `firebase-common`'s data-collection setting calls them. No `getFloat`: nothing calls it. |
| README | `README.md` | Drop the long-key warning. Keep "must be thread-safe" and the `ConcurrentHashMap` example, since Auth's token refresh writes from network threads. State that preferences live under `getFilesDir()`. |
| AOSP headers | `JsonReader.java`, `JsonScope.java`, `JsonToken.java`, `MalformedJsonException.java`, `Base64DataException.java`, `com/android/internal/util/StringPool.java` | A short comment above the license header: a verbatim AOSP port, and which library needs it. |

Unchanged: `getFilesDir()`'s default (temp folder, like `getDatabasePath`) and
the URL-encoding of file names.

Heartbeat values that earlier versions wrote to users' stores under the bare
keys `fire-global` and `last-used-date` stay there, unused. No migration:
nothing else was persisted through these preference files before this branch.

## Testing

TDD throughout. `PreferencesFileTest` replaces `PlatformSharedPreferencesTest`
and uses a fresh temp folder per test:

- Values round-trip and `getAll()` returns them typed (string, int, long,
  boolean, string set).
- Two files do not see each other's keys; `clear()` affects only its own file.
- Batching: `edit().putString(k, v).clear().commit()` keeps `k`.
- `commit()` writes valid JSON to `shared_prefs/<name>.json` and leaves no
  temp files.
- A file already on disk is loaded when first opened. With the previous test,
  this covers persistence across a restart without test hooks in production
  code.
- Eight threads committing concurrently all land in the file.
- A missing file reads as empty; a corrupt file is logged, reads as empty, and
  is overwritten by the next commit.

`HeartBeatTest`, `ContextFilesTest`, and `FirebaseRemoteConfigTest` stay.

Flaky-test fixes:

- `FirebaseTest` gives each test its own temp `getFilesDir()`, since
  preferences are now on disk. `FirebaseTest` and `FakeFirebasePlatform` use a
  `ConcurrentHashMap` store.
- `FirebaseStorageTest` waits for its `downloadUrl` task to finish without
  asserting the outcome, so its logs stay within that test.

Verification: the full suite inside the Auth emulator, plus `ktlintCheck`,
with clean output.

## Out of scope

- A persistent per-user default for `getFilesDir()`.
- Windows `File.renameTo` in Installations' own `PersistedInstallation`
  (documented in the PR).
- Realtime updates; they get their own spec.

## Design documents

This spec and its plan are committed on the branch while work is in progress
and removed in one commit before pushing, so the PR stays code-only.
