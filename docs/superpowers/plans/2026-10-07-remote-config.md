# Remote Config Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the bundled Android Remote Config 21.6.0 and Installations 17.2.0 libraries work on the JVM by filling the Android shims they call.

**Architecture:** The SDK runs unmodified Android Firebase code against hand-written `android.*` shims in `src/main/java`. This plan adds file APIs to `Context`, platform-backed `SharedPreferences` for two preference files, ports `JsonReader` from AOSP, adds three small shims, and registers the Remote Config and ABT component registrars. No Remote Config logic is written here.

**Tech Stack:** Kotlin 2.0.20 / Java 17, Gradle 8.13, JUnit 4, kotlinx-coroutines-test (`runTest`), kotlinx-coroutines-play-services (`await`), kotlinx-serialization-json (already an `implementation` dependency).

**Spec:** `docs/superpowers/specs/2026-10-07-remote-config-design.md`

## Global Constraints

- Run every Gradle command with `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr`. The default JDK 25 fails at configuration with only `> 25.0.4.1`.
- Run the full suite inside the Auth emulator, as CI does: `firebase emulators:exec --project my-firebase-project --import=src/test/resources/firebase_data '<gradle command>'`. Without it, the 6 `FirebaseAuthTest` tests fail with `ConnectException ... localhost:9099`. Baseline on `master`: 12/12 pass and `ktlintCheck` is clean.
- Single test classes may run without the emulator: `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr ./gradlew test --tests '<Class>'`. Read failures with `grep -A8 '<failure' build/test-results/test/TEST-<Class>.xml`.
- `ktlintCheck` must pass (ktlint 0.47.1). Do not write trailing commas. If it fails on formatting only, run `./gradlew ktlintFormat` and do not hand-edit whitespace.
- Every new hand-written file starts with a brief header comment stating its purpose and how it fits the project. Verbatim AOSP ports keep the AOSP license header and class javadoc unchanged; those serve as the header.
- Match the style of the file you edit. Add no dependencies.
- Commit messages carry no AI attribution (no `Co-Authored-By`, no session links).
- If a test fails for a reason this plan does not predict, stop. Find the root cause, report it, and wait for Angelos before changing course. Do not add workarounds.
- Out of scope: realtime updates (`addOnConfigUpdateListener`), `setDefaultsAsync(@XmlRes int)`, real `PackageManager.getPackageInfo` data.

## Review Focus

Inputs the spec implies but its listed tests do not exercise, most likely first. Each has a test in the owning task.

1. **App IDs contain `:`**, and Remote Config names its files `frc_<appId>_firebase_{fetch,activate,defaults}.json`. Windows forbids `:` in file names, so these files must still read and write there. Task 1, `file names with colons are stored without colons`.
2. **Concurrent preference writes.** Remote Config writes metadata from background executors. A lost update to the key index would make `reset()` leave stale metadata behind. Task 2, `concurrent writes are all cleared`.
3. **App restart.** Values written through one preferences instance must be readable from a fresh instance, as after a process restart. Task 2, `values persist across instances`.
4. **First run on a clean machine.** `getFilesDir()` may not exist yet, and a missing config file must raise `FileNotFoundException`, which Remote Config reads as "no config yet". Task 1, `filesDir is created when missing` and `opening a missing file throws FileNotFoundException`.
5. **Other products keep failing loudly.** Preference files other than the two platform-backed ones must still throw on unknown keys, including the new `getInt`/`putInt`/`clear`. Task 2, `other preference files still reject unknown keys`.

---

### Task 1: File storage on `Context`

**Files:**
- Modify: `src/main/java/com/google/firebase/FirebasePlatform.kt`
- Modify: `src/main/java/android/content/Context.kt`
- Modify: `src/test/kotlin/fakes/FakeFirebasePlatform.kt`
- Test: `src/test/kotlin/ContextFilesTest.kt` (create)

**Interfaces:**
- Produces: `FirebasePlatform.getFilesDir(): File` (open, default `<tmpdir>/firebase-files`); `Context.filesDir: File` (Java `getFilesDir()`), `Context.openFileInput(name: String): FileInputStream`, `Context.openFileOutput(name: String, mode: Int): FileOutputStream`, `Context.deleteFile(name: String): Boolean`, `Context.MODE_APPEND = 0x8000`; `FakeFirebasePlatform(storage, databaseFolderPath, filesFolderPath)`.

- [ ] **Step 1: Give `FakeFirebasePlatform` a files folder**

In `src/test/kotlin/fakes/FakeFirebasePlatform.kt`, add a constructor parameter and override:

```kotlin
class FakeFirebasePlatform(
    val storage: MutableMap<String, String> = mutableMapOf(),
    databaseFolderPath: String = "./build/database/",
    filesFolderPath: String = "./build/files/"
) : FirebasePlatform() {

    private val databaseFolder = File(databaseFolderPath)

    private val filesFolder = File(filesFolderPath)
```

and after `getDatabasePath`:

```kotlin
    override fun getFilesDir() = filesFolder
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/kotlin/ContextFilesTest.kt`:

```kotlin
/*
 * Tests for the file APIs on the android.content.Context shim, which Remote Config and
 * Installations use to persist configs and installation data in FirebasePlatform.getFilesDir().
 */
import android.app.Application
import android.content.Context
import com.google.firebase.FirebasePlatform
import fakes.FakeFirebasePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

class ContextFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var filesDir: File

    private val context = Application()

    @Before
    fun setUp() {
        filesDir = File(folder.root, "files")
        FirebasePlatform.initializeFirebasePlatform(FakeFirebasePlatform(filesFolderPath = filesDir.path))
    }

    @Test
    fun `filesDir is created when missing`() {
        assertFalse(filesDir.exists())
        assertEquals(filesDir, context.filesDir)
        assertTrue(filesDir.isDirectory)
    }

    @Test
    fun `written file can be read back`() {
        context.openFileOutput("config.json", 0).use { it.write("hello".toByteArray()) }
        assertEquals("hello", context.openFileInput("config.json").use { String(it.readBytes()) })
    }

    @Test
    fun `opening a missing file throws FileNotFoundException`() {
        assertThrows(FileNotFoundException::class.java) { context.openFileInput("missing.json") }
    }

    @Test
    fun `deleteFile removes the file`() {
        context.openFileOutput("config.json", 0).use { it.write("hello".toByteArray()) }
        assertTrue(context.deleteFile("config.json"))
        assertThrows(FileNotFoundException::class.java) { context.openFileInput("config.json") }
        assertFalse(context.deleteFile("config.json"))
    }

    @Test
    fun `append mode appends`() {
        context.openFileOutput("log.txt", 0).use { it.write("a".toByteArray()) }
        context.openFileOutput("log.txt", Context.MODE_APPEND).use { it.write("b".toByteArray()) }
        assertEquals("ab", context.openFileInput("log.txt").use { String(it.readBytes()) })
    }

    @Test
    fun `file names with colons are stored without colons`() {
        val name = "frc_1:341458593155:web:bf8e1aa37efe01f32d42b6_firebase_fetch.json"
        context.openFileOutput(name, 0).use { it.write("{}".toByteArray()) }
        assertEquals("{}", context.openFileInput(name).use { String(it.readBytes()) })
        assertTrue(filesDir.list()!!.none { ':' in it })
    }

    @Test
    fun `file names with path separators are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { context.openFileOutput("../escape.json", 0) }
    }
}
```

- [ ] **Step 3: Run the tests and confirm they fail**

Run: `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr ./gradlew test --tests 'ContextFilesTest'`
Expected: test compilation fails: `getFilesDir` overrides nothing in `FakeFirebasePlatform`, and `filesDir`, `openFileOutput`, `openFileInput`, `deleteFile`, `MODE_APPEND` are unresolved.

- [ ] **Step 4: Add `getFilesDir` to `FirebasePlatform`**

In `src/main/java/com/google/firebase/FirebasePlatform.kt`, after `getDatabasePath`:

```kotlin
    open fun getFilesDir(): File = File("${System.getProperty("java.io.tmpdir")}${File.separatorChar}firebase-files")
```

- [ ] **Step 5: Add the file APIs to `Context`**

In `src/main/java/android/content/Context.kt`, add imports `java.io.FileInputStream`, `java.io.FileOutputStream`, `java.net.URLEncoder`. After `getDatabasePath`, add:

```kotlin
    val filesDir: File
        get() = FirebasePlatform.firebasePlatform.getFilesDir().apply { mkdirs() }

    fun openFileInput(name: String): FileInputStream = FileInputStream(fileStreamPath(name))

    fun openFileOutput(name: String, mode: Int): FileOutputStream =
        FileOutputStream(fileStreamPath(name), mode and MODE_APPEND != 0)

    fun deleteFile(name: String): Boolean = fileStreamPath(name).delete()

    // URL-encodes the name so characters such as ':' in Firebase app IDs are valid on every OS
    private fun fileStreamPath(name: String): File {
        require('/' !in name && File.separatorChar !in name) { "File $name contains a path separator" }
        return File(filesDir, URLEncoder.encode(name, Charsets.UTF_8))
    }
```

In the companion object, add:

```kotlin
        const val MODE_APPEND = 0x8000
```

- [ ] **Step 6: Run the tests and confirm they pass**

Run: `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr ./gradlew test --tests 'ContextFilesTest' ktlintCheck`
Expected: 7 tests pass; ktlint clean.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/google/firebase/FirebasePlatform.kt src/main/java/android/content/Context.kt src/test/kotlin/fakes/FakeFirebasePlatform.kt src/test/kotlin/ContextFilesTest.kt
git commit -m "Add file storage APIs to Context backed by FirebasePlatform.getFilesDir"
```

---

### Task 2: Platform-backed preferences for Remote Config and Installations

**Files:**
- Modify: `src/main/java/android/content/SharedPreferences.java`
- Create: `src/main/java/android/content/PlatformSharedPreferences.kt`
- Modify: `src/main/java/android/content/Context.kt` (`getSharedPreferences`)
- Test: `src/test/kotlin/PlatformSharedPreferencesTest.kt` (create)

**Interfaces:**
- Consumes: `FakeFirebasePlatform(storage, ...)` from Task 1.
- Produces: `SharedPreferences.getInt(String, int)`, `Editor.putInt(String, int)`, `Editor.clear()`; `internal class PlatformSharedPreferences(name: String) : SharedPreferences`. `Context.getSharedPreferences` returns it for names starting with `frc_` and for `com.google.android.gms.appid`. Storage keys: `"<name>|<key>"`; key index: `"<name>|__keys"` (JSON array).

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/PlatformSharedPreferencesTest.kt`:

```kotlin
/*
 * Tests for the SharedPreferences that Context returns for Remote Config ("frc_*") and
 * Installations ("com.google.android.gms.appid"), persisted through FirebasePlatform.
 */
import android.app.Application
import com.google.firebase.FirebasePlatform
import fakes.FakeFirebasePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.concurrent.thread

class PlatformSharedPreferencesTest {

    private val platform = FakeFirebasePlatform()

    private val context = Application()

    @Before
    fun setUp() {
        FirebasePlatform.initializeFirebasePlatform(platform)
    }

    @Test
    fun `values round-trip by type`() {
        val prefs = context.getSharedPreferences("frc_app_firebase_settings", 0)
        prefs.edit()
            .putString("last_fetch_etag", "abc")
            .putLong("last_fetch_time_in_millis", 42L)
            .putInt("last_fetch_status", -1)
            .commit()

        assertEquals("abc", prefs.getString("last_fetch_etag", null))
        assertEquals(42L, prefs.getLong("last_fetch_time_in_millis", 0L))
        assertEquals(-1, prefs.getInt("last_fetch_status", 0))
        assertEquals(7, prefs.getInt("missing", 7))
        assertTrue(prefs.contains("last_fetch_etag"))
        assertFalse(prefs.contains("missing"))
    }

    @Test
    fun `putString null removes the key`() {
        val prefs = context.getSharedPreferences("frc_app_firebase_settings", 0)
        prefs.edit().putString("last_fetch_etag", "abc").commit()
        prefs.edit().putString("last_fetch_etag", null).commit()

        assertFalse(prefs.contains("last_fetch_etag"))
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `files do not see each other's keys`() {
        val first = context.getSharedPreferences("frc_first_firebase_settings", 0)
        val second = context.getSharedPreferences("frc_second_firebase_settings", 0)
        first.edit().putString("key", "first").commit()

        assertNull(second.getString("key", null))
    }

    @Test
    fun `clear removes only this file's keys`() {
        val first = context.getSharedPreferences("frc_first_firebase_settings", 0)
        val second = context.getSharedPreferences("frc_second_firebase_settings", 0)
        first.edit().putString("key", "first").commit()
        second.edit().putString("key", "second").commit()

        first.edit().clear().commit()

        assertNull(first.getString("key", null))
        assertTrue(first.all.isEmpty())
        assertEquals("second", second.getString("key", null))
        assertTrue(platform.storage.keys.none { it.startsWith("frc_first_firebase_settings|") })
    }

    @Test
    fun `values persist across instances`() {
        context.getSharedPreferences("frc_app_firebase_settings", 0).edit().putLong("num_failed_fetches", 3L).commit()

        assertEquals(3L, Application().getSharedPreferences("frc_app_firebase_settings", 0).getLong("num_failed_fetches", 0L))
    }

    @Test
    fun `installations preferences are platform-backed`() {
        val prefs = context.getSharedPreferences("com.google.android.gms.appid", 0)

        assertNull(prefs.getString("|S|id", null))
    }

    @Test
    fun `concurrent writes are all cleared`() {
        val prefs = context.getSharedPreferences("frc_app_firebase_settings", 0)
        (0 until 8).map { i ->
            thread { repeat(50) { j -> prefs.edit().putInt("key-$i-$j", j).apply() } }
        }.forEach { it.join() }

        assertEquals(400, prefs.all.size)
        prefs.edit().clear().commit()
        assertTrue(platform.storage.keys.none { it.startsWith("frc_app_firebase_settings|") })
    }

    @Test
    fun `other preference files still reject unknown keys`() {
        val prefs = context.getSharedPreferences("FirebaseHeartBeat", 0)

        assertThrows(IllegalArgumentException::class.java) { prefs.getInt("unknown", 0) }
        assertThrows(IllegalArgumentException::class.java) { prefs.edit().putInt("unknown", 0) }
        assertThrows(IllegalArgumentException::class.java) { prefs.edit().clear() }
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr ./gradlew test --tests 'PlatformSharedPreferencesTest'`
Expected: test compilation fails with unresolved `putInt`, `getInt`, and `clear`.

- [ ] **Step 3: Add the interface methods**

In `src/main/java/android/content/SharedPreferences.java`, after `long getLong(String key, long defValue);` add:

```java
    int getInt(String key, int defValue);
```

and in `Editor`, after `Editor putString(String key, String value);` add:

```java
        Editor putInt(String key, int value);
        Editor clear();
```

- [ ] **Step 4: Implement the new methods in the strict preferences**

In `src/main/java/android/content/Context.kt`, inside the anonymous `SharedPreferences` in `getSharedPreferences`, after `getLong`:

```kotlin
            override fun getInt(key: String?, defValue: Int): Int {
                throw IllegalArgumentException(key)
            }
```

and inside the anonymous `Editor`, after `putString`:

```kotlin
                    override fun putInt(key: String?, value: Int): Editor {
                        throw IllegalArgumentException(key)
                    }

                    override fun clear(): Editor {
                        throw IllegalArgumentException(name)
                    }
```

- [ ] **Step 5: Create `PlatformSharedPreferences`**

Create `src/main/java/android/content/PlatformSharedPreferences.kt`:

```kotlin
/*
 * SharedPreferences persisted through FirebasePlatform, returned by Context.getSharedPreferences
 * for the preference files of Remote Config and Installations. Each key is stored as
 * "<file>|<key>", and the file's key set is stored under "<file>|__keys" so clear() can find them.
 * Puts are written immediately, so commit() and apply() have nothing left to do.
 */
package android.content

import com.google.firebase.FirebasePlatform
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal class PlatformSharedPreferences(private val name: String) : SharedPreferences {

    private val platform: FirebasePlatform
        get() = FirebasePlatform.firebasePlatform

    override fun contains(key: String): Boolean = platform.retrieve(storageKey(key)) != null

    override fun getString(key: String, defaultValue: String?): String? = platform.retrieve(storageKey(key)) ?: defaultValue

    override fun getLong(key: String, defValue: Long): Long = platform.retrieve(storageKey(key))?.toLong() ?: defValue

    override fun getInt(key: String, defValue: Int): Int = platform.retrieve(storageKey(key))?.toInt() ?: defValue

    override fun getAll(): Map<String, String> = synchronized(lock) {
        keys().mapNotNull { key -> platform.retrieve(storageKey(key))?.let { key to it } }.toMap()
    }

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            put(key, value)
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            put(key, value.toString())
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            put(key, value.toString())
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll()
            return this
        }

        override fun commit(): Boolean = true

        override fun apply() {
        }
    }

    private fun put(key: String, value: String?) = synchronized(lock) {
        if (value == null) {
            platform.clear(storageKey(key))
            writeKeys(keys() - key)
        } else {
            platform.store(storageKey(key), value)
            writeKeys(keys() + key)
        }
    }

    private fun clearAll() = synchronized(lock) {
        keys().forEach { platform.clear(storageKey(it)) }
        platform.clear(keysKey)
    }

    private fun keys(): Set<String> = platform.retrieve(keysKey)?.let { Json.decodeFromString(keySetSerializer, it) } ?: emptySet()

    private fun writeKeys(keys: Set<String>) = platform.store(keysKey, Json.encodeToString(keySetSerializer, keys))

    private fun storageKey(key: String) = "$name|$key"

    private val keysKey: String
        get() = "$name|__keys"

    companion object {
        private val lock = Any()
        private val keySetSerializer = SetSerializer(String.serializer())
    }
}
```

- [ ] **Step 6: Route the two preference files to it**

In `src/main/java/android/content/Context.kt`, make the first lines of `getSharedPreferences`:

```kotlin
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        if (name.startsWith("frc_") || name == "com.google.android.gms.appid") {
            return PlatformSharedPreferences(name)
        }
        return object : SharedPreferences {
```

- [ ] **Step 7: Run the tests and confirm they pass**

Run: `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr ./gradlew test --tests 'PlatformSharedPreferencesTest' --tests 'ContextFilesTest' ktlintCheck`
Expected: 8 + 7 tests pass; ktlint clean.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/android/content/SharedPreferences.java src/main/java/android/content/PlatformSharedPreferences.kt src/main/java/android/content/Context.kt src/test/kotlin/PlatformSharedPreferencesTest.kt
git commit -m "Persist Remote Config and Installations preferences through FirebasePlatform"
```

---

### Task 3: Port `android.util.JsonReader` from AOSP

Installations parses its REST responses with `android.util.JsonReader`, which has no shim.

**Files:**
- Create (verbatim AOSP ports): `src/main/java/android/util/JsonReader.java`, `src/main/java/android/util/JsonToken.java`, `src/main/java/android/util/JsonScope.java`, `src/main/java/android/util/MalformedJsonException.java`, `src/main/java/com/android/internal/util/StringPool.java`
- Test: `src/test/kotlin/JsonReaderTest.kt` (create)

**Interfaces:**
- Produces: `android.util.JsonReader(Reader)` with the Android API (`beginObject`, `endObject`, `hasNext`, `nextName`, `nextString`, `skipValue`, `close`).

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/JsonReaderTest.kt`:

```kotlin
/*
 * Tests the android.util.JsonReader port on the response shape Firebase Installations parses.
 */
import android.util.JsonReader
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

class JsonReaderTest {

    @Test
    fun `parses an installations response`() {
        val json = """{"name":"projects/1/installations/fid-123","fid":"fid-123","refreshToken":"refresh","authToken":{"token":"auth","expiresIn":"604800s"},"unknown":[1,{"a":true}]}"""
        val values = mutableMapOf<String, String>()

        JsonReader(StringReader(json)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (val name = reader.nextName()) {
                    "fid", "refreshToken" -> values[name] = reader.nextString()
                    "authToken" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            values["authToken." + reader.nextName()] = reader.nextString()
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        assertEquals(
            mapOf(
                "fid" to "fid-123",
                "refreshToken" to "refresh",
                "authToken.token" to "auth",
                "authToken.expiresIn" to "604800s"
            ),
            values
        )
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr ./gradlew test --tests 'JsonReaderTest'`
Expected: test compilation fails with `Unresolved reference: JsonReader`. Robolectric's `android-all` is `compileOnly` for main and is not on the test classpath.

- [ ] **Step 3: Copy the AOSP sources verbatim**

```bash
for f in JsonReader JsonToken JsonScope MalformedJsonException; do
  gh api "repos/aosp-mirror/platform_frameworks_base/contents/core/java/android/util/$f.java?ref=android14-release" \
    -H 'Accept: application/vnd.github.raw' > src/main/java/android/util/$f.java
done
gh api "repos/aosp-mirror/platform_frameworks_base/contents/core/java/com/android/internal/util/StringPool.java?ref=android14-release" \
  -H 'Accept: application/vnd.github.raw' > src/main/java/com/android/internal/util/StringPool.java
wc -l src/main/java/android/util/Json*.java src/main/java/android/util/MalformedJsonException.java src/main/java/com/android/internal/util/StringPool.java
```

Expected line counts: JsonReader 1173, JsonToken 82, JsonScope 68, MalformedJsonException 31, StringPool 77. Each file must start with the AOSP Apache 2.0 header. Do not edit the files.

- [ ] **Step 4: Run the test and confirm it passes**

Run: `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr ./gradlew test --tests 'JsonReaderTest' ktlintCheck`
Expected: PASS; ktlint clean (it does not check Java).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/android/util/JsonReader.java src/main/java/android/util/JsonToken.java src/main/java/android/util/JsonScope.java src/main/java/android/util/MalformedJsonException.java src/main/java/com/android/internal/util/StringPool.java src/test/kotlin/JsonReaderTest.kt
git commit -m "Port android.util.JsonReader from AOSP for Firebase Installations"
```

---

### Task 4: Register Remote Config and port the offline tests

**Files:**
- Modify: `src/main/java/android/content/pm/PackageManager.java` (`getServiceInfo`)
- Modify: `src/test/kotlin/FirebaseTest.kt` (platform `getFilesDir`)
- Test: `src/test/kotlin/FirebaseRemoteConfigTest.kt` (create)

**Interfaces:**
- Consumes: everything from Tasks 1–3.
- Produces: `FirebaseRemoteConfigTest` with fields `defaults: Map<String, Any>` and `remoteConfig: FirebaseRemoteConfig`, which Task 5 extends.

The tests port firebase-kotlin-sdk `firebase-config/src/commonTest/kotlin/dev/gitlive/firebase/remoteconfig/FirebaseRemoteConfig.kt` one-to-one onto the Android API: same names, keys, values, and assertions.

- [ ] **Step 1: Point test files at `build/`**

In `src/test/kotlin/FirebaseTest.kt`, inside the `FirebasePlatform` object after `getDatabasePath`:

```kotlin
                override fun getFilesDir() = File("./build/files")
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/kotlin/FirebaseRemoteConfigTest.kt`:

```kotlin
/*
 * Remote Config tests ported one-to-one from firebase-kotlin-sdk's common tests
 * (firebase-config/src/commonTest/.../FirebaseRemoteConfig.kt) onto the Android API,
 * so the Kotlin SDK's JVM target behaves the same when it runs on this SDK.
 */
import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.FirebaseOptions
import com.google.firebase.initialize
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.google.firebase.remoteconfig.FirebaseRemoteConfigValue
import com.google.firebase.remoteconfig.get
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Ignore
import org.junit.Test

class FirebaseRemoteConfigTest : FirebaseTest() {

    private val defaults = mapOf<String, Any>(
        "test_default_boolean" to true,
        "test_default_double" to 42.0,
        "test_default_long" to 42L,
        "test_default_string" to "Hello World"
    )

    private val remoteConfig: FirebaseRemoteConfig by lazy { Firebase.remoteConfig(app) }

    @After
    fun tearDown(): Unit = runTest {
        remoteConfig.reset().await()
    }

    @Test
    fun testGettingValues(): Unit = runTest {
        remoteConfig.setDefaultsAsync(defaults).await()

        assertEquals(true, remoteConfig.getBoolean("test_default_boolean"))
        assertEquals(42.0, remoteConfig.getDouble("test_default_double"), 0.0)
        assertEquals(42L, remoteConfig.getLong("test_default_long"))
        assertEquals("Hello World", remoteConfig.getString("test_default_string"))
        assertEquals("Hello World", remoteConfig.getString("test_default_string"))

        val value: FirebaseRemoteConfigValue = remoteConfig["test_default_string"]
        assertEquals("Hello World", value.asString())
        assertEquals(FirebaseRemoteConfig.VALUE_SOURCE_DEFAULT, value.source)
        assertEquals("Hello World", value.asByteArray().decodeToString())
    }

    @Test
    fun testNamedApp(): Unit = runTest {
        // Android keys Remote Config's local storage by app ID, so the named app needs its own
        val options = FirebaseOptions.Builder(app.options).setApplicationId("1:341458593155:web:bf8e1aa37efe01f32d42b7").build()
        val namedApp = Firebase.initialize(Application(), options, "named")
        val namedRemoteConfig = Firebase.remoteConfig(namedApp)
        namedRemoteConfig.setDefaultsAsync(mapOf("named_app_only" to "named")).await()

        assertEquals("named", namedRemoteConfig.getValue("named_app_only").asString())
        assertFalse(remoteConfig.all.containsKey("named_app_only"))
        namedRemoteConfig.reset().await()
    }

    @Test
    fun testGetAll(): Unit = runTest {
        remoteConfig.setDefaultsAsync(defaults).await()
        val all = remoteConfig.all
        assertEquals(true, all["test_default_boolean"]?.asBoolean())
        assertEquals(42.0, all["test_default_double"]?.asDouble())
        assertEquals(42L, all["test_default_long"]?.asLong())
        assertEquals("Hello World", all["test_default_string"]?.asString())
        assertEquals("Hello World", all["test_default_string"]?.asByteArray()?.decodeToString())
    }

    @Test
    fun testGetKeysByPrefix(): Unit = runTest {
        remoteConfig.setDefaultsAsync(defaults).await()
        val keys = remoteConfig.getKeysByPrefix("test_default")
        assertEquals(
            setOf(
                "test_default_boolean",
                "test_default_double",
                "test_default_long",
                "test_default_string"
            ),
            keys
        )
    }

    @Test
    fun testGetInfo(): Unit = runTest {
        val info = remoteConfig.info
        val defaultSettings = FirebaseRemoteConfigSettings.Builder().build()
        assertEquals(defaultSettings.fetchTimeoutInSeconds, info.configSettings.fetchTimeoutInSeconds)
        assertEquals(defaultSettings.minimumFetchIntervalInSeconds, info.configSettings.minimumFetchIntervalInSeconds)
        assertEquals(-1L, info.fetchTimeMillis)
        assertEquals(FirebaseRemoteConfig.LAST_FETCH_STATUS_NO_FETCH_YET, info.lastFetchStatus)
    }

    @Test
    fun testSetConfigSettings(): Unit = runTest {
        remoteConfig.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder()
                .setFetchTimeoutInSeconds(42)
                .setMinimumFetchIntervalInSeconds(42)
                .build()
        ).await()
        val info = remoteConfig.info
        assertEquals(42L, info.configSettings.fetchTimeoutInSeconds)
        assertEquals(42L, info.configSettings.minimumFetchIntervalInSeconds)
    }

    // Unfortunately Firebase Remote Config is not implemented by Firebase emulator so it may be
    // tested against a real project only. Add "test_remote_string": "Hello from remote!" config
    // value in Firebase console for enabling this test case.
    @Test
    @Ignore
    fun testFetch(): Unit = runTest {
        remoteConfig.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder().setMinimumFetchIntervalInSeconds(60).build()
        ).await()

        remoteConfig.fetch().await()
        remoteConfig.activate().await()

        val value: FirebaseRemoteConfigValue = remoteConfig["test_remote_string"]
        assertEquals("Hello from remote!", value.asString())
        assertEquals(FirebaseRemoteConfig.VALUE_SOURCE_REMOTE, value.source)
    }

    @Test
    @Ignore
    fun testFetchAndActivate(): Unit = runTest {
        remoteConfig.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder().setMinimumFetchIntervalInSeconds(60).build()
        ).await()

        remoteConfig.fetchAndActivate().await()

        val value: FirebaseRemoteConfigValue = remoteConfig["test_remote_string"]
        assertEquals("Hello from remote!", value.asString())
        assertEquals(FirebaseRemoteConfig.VALUE_SOURCE_REMOTE, value.source)
    }
}
```

- [ ] **Step 3: Run the tests and confirm they fail**

Run: `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr ./gradlew test --tests 'FirebaseRemoteConfigTest'`
Then: `grep -A8 '<failure' build/test-results/test/TEST-FirebaseRemoteConfigTest.xml`
Expected: the 6 active tests fail because component discovery cannot provide `com.google.firebase.remoteconfig.RemoteConfigComponent` (the message names that class). The 2 `@Ignore` tests are skipped. Any other failure: stop and report (Global Constraints).

- [ ] **Step 4: Register the registrars**

In `src/main/java/android/content/pm/PackageManager.java`, in `getServiceInfo`, after the `StorageRegistrar` line:

```java
                data.put("com.google.firebase.components:com.google.firebase.remoteconfig.RemoteConfigRegistrar", "com.google.firebase.components.ComponentRegistrar");
                data.put("com.google.firebase.components:com.google.firebase.abt.component.AbtRegistrar", "com.google.firebase.components.ComponentRegistrar");
```

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr ./gradlew test --tests 'FirebaseRemoteConfigTest' ktlintCheck`
Expected: 6 pass, 2 skipped; ktlint clean. If anything fails, or a test hangs until `runTest` times out, read the failure and test stderr for `NoClassDefFoundError` / `NoSuchMethodError` / `AbstractMethodError` naming an `android.*` member. That is an unscanned shim gap: stop and report it.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/android/content/pm/PackageManager.java src/test/kotlin/FirebaseTest.kt src/test/kotlin/FirebaseRemoteConfigTest.kt
git commit -m "Register Remote Config and port firebase-kotlin-sdk Remote Config tests"
```

---

### Task 5: Live fetch through Installations

**Files:**
- Create: `src/main/java/android/net/TrafficStats.java`
- Create: `src/main/java/android/content/res/Configuration.java`
- Modify: `src/main/java/android/content/res/Resources.java`
- Create: `src/main/java/android/text/format/DateUtils.java`
- Test: `src/test/kotlin/FirebaseRemoteConfigTest.kt` (add one test)
- Test: `src/test/kotlin/DateUtilsTest.kt` (create)

**Interfaces:**
- Consumes: `FirebaseRemoteConfigTest.remoteConfig` from Task 4.
- Produces: `TrafficStats.setThreadStatsTag(int)`, `TrafficStats.clearThreadStatsTag()`; `Configuration.locale: Locale`; `Resources.getConfiguration(): Configuration`; `DateUtils.formatElapsedTime(long): String`.

- [ ] **Step 1: Write the failing live test**

Add to `FirebaseRemoteConfigTest`, after `testSetConfigSettings`:

```kotlin
    @Test
    fun `fetchAndActivate succeeds`(): Unit = runTest {
        remoteConfig.fetchAndActivate().await()

        assertEquals(FirebaseRemoteConfig.LAST_FETCH_STATUS_SUCCESS, remoteConfig.info.lastFetchStatus)
    }
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr ./gradlew test --tests 'FirebaseRemoteConfigTest'`
Expected: `fetchAndActivate succeeds` fails with `NoClassDefFoundError: android/net/TrafficStats`. It may also show as a `runTest` timeout, because an `Error` thrown on a Firebase executor never completes the `Task`. In that case, find the `NoClassDefFoundError` in the test's stderr in `build/test-results/test/TEST-FirebaseRemoteConfigTest.xml`.

- [ ] **Step 3: Add `TrafficStats`**

Create `src/main/java/android/net/TrafficStats.java`:

```java
/*
 * No-op shim for android.net.TrafficStats. Firebase Installations tags its network thread
 * for Android's per-app traffic accounting, which has no JVM equivalent.
 */
package android.net;

public class TrafficStats {

    public static void setThreadStatsTag(int tag) {
    }

    public static void clearThreadStatsTag() {
    }
}
```

- [ ] **Step 4: Run it and confirm the next failure**

Run: same as Step 2.
Expected: fails with `NoSuchMethodError` for `android.content.res.Resources.getConfiguration()`, or `NoClassDefFoundError: android/content/res/Configuration`, from `ConfigFetchHttpClient`. A different failure, such as an HTTP 4xx from Installations or Remote Config: stop and report.

- [ ] **Step 5: Add `Configuration` and `Resources.getConfiguration`**

Create `src/main/java/android/content/res/Configuration.java`:

```java
/*
 * Shim for android.content.res.Configuration. Remote Config reads the locale from it
 * and sends it with each fetch request.
 */
package android.content.res;

import java.util.Locale;

public class Configuration {
    public Locale locale = Locale.getDefault();
}
```

In `src/main/java/android/content/res/Resources.java`, add inside the class, before `NotFoundException`:

```java
    public Configuration getConfiguration() {
        return new Configuration();
    }

```

- [ ] **Step 6: Run it and confirm it passes**

Run: same as Step 2.
Expected: 7 pass, 2 skipped.

- [ ] **Step 7: Write the failing `DateUtils` test**

`ConfigFetchHandler` calls `DateUtils.formatElapsedTime` when it builds a throttling message. The live test cannot reach that path, so test it directly. Create `src/test/kotlin/DateUtilsTest.kt`:

```kotlin
/*
 * Tests the android.text.format.DateUtils shim, used by Remote Config to format
 * the remaining throttle time in fetch errors.
 */
import android.text.format.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class DateUtilsTest {

    @Test
    fun `formats minutes and seconds`() {
        assertEquals("00:00", DateUtils.formatElapsedTime(0))
        assertEquals("01:15", DateUtils.formatElapsedTime(75))
    }

    @Test
    fun `formats hours when present`() {
        assertEquals("1:02:05", DateUtils.formatElapsedTime(3725))
    }
}
```

- [ ] **Step 8: Run it and confirm it fails**

Run: `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr ./gradlew test --tests 'DateUtilsTest'`
Expected: test compilation fails with `Unresolved reference: DateUtils`.

- [ ] **Step 9: Add `DateUtils`**

Create `src/main/java/android/text/format/DateUtils.java`:

```java
/*
 * Shim for android.text.format.DateUtils with the elapsed-time formatting Remote Config
 * uses in its throttling messages: "MM:SS", or "H:MM:SS" when hours are present.
 */
package android.text.format;

public class DateUtils {

    public static String formatElapsedTime(long elapsedSeconds) {
        long hours = elapsedSeconds / 3600;
        long minutes = (elapsedSeconds % 3600) / 60;
        long seconds = elapsedSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }
}
```

- [ ] **Step 10: Run the tests and confirm they pass**

Run: `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr ./gradlew test --tests 'DateUtilsTest' --tests 'FirebaseRemoteConfigTest' ktlintCheck`
Expected: 2 + 7 pass, 2 skipped; ktlint clean.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/android/net/TrafficStats.java src/main/java/android/content/res/Configuration.java src/main/java/android/content/res/Resources.java src/main/java/android/text/format/DateUtils.java src/test/kotlin/FirebaseRemoteConfigTest.kt src/test/kotlin/DateUtilsTest.kt
git commit -m "Add shims for Remote Config fetch through Firebase Installations"
```

---

### Task 6: Documentation and full verification

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Document `getFilesDir`**

In `README.md`, after the paragraph ending "This is used by Firestore to support [offline data persistence](...)." insert:

````markdown
#### Customizing file storage location

The `FirebasePlatform` interface also includes a `getFilesDir` method for you to override if the following default implementation is not suitable:

```kotlin
    open fun getFilesDir(): File = File("${System.getProperty("java.io.tmpdir")}${File.separatorChar}firebase-files")
```

This is used by Remote Config and Installations to persist fetched configs and the installation ID.
````

- [ ] **Step 2: Update the project status table**

Replace the two struck-through rows and footnote 2:

```markdown
| [Remote Config](https://firebase.google.com/docs/remote-config)                                   | `21.6.0`[^2]            |
| [Installations](https://firebase.google.com/docs/projects/manage-installations)                   | `17.2.0`                |
```

```markdown
[^2]: Realtime updates (`addOnConfigUpdateListener`) and defaults from XML resources are not supported.
```

- [ ] **Step 3: Run the full suite the way CI does**

Run:
```bash
JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr firebase emulators:exec --project my-firebase-project --import=src/test/resources/firebase_data './gradlew cleanTest build'
grep -hoE 'testsuite name="[^"]*" tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' build/test-results/test/*.xml
```
Expected: `BUILD SUCCESSFUL`. 12 baseline tests plus 7 `ContextFilesTest`, 8 `PlatformSharedPreferencesTest`, 1 `JsonReaderTest`, 2 `DateUtilsTest`, and 9 `FirebaseRemoteConfigTest` (2 skipped). 0 failures, 0 errors.

- [ ] **Step 4: Check test output is clean**

Run: `grep -lE 'Exception|ERROR|WARN' build/test-results/test/*.xml` and read every `<system-out>`/`<system-err>` block in the matching files.
Expected: no exceptions or warnings from Remote Config, Installations, or the shims. Report any you find to Angelos with the exact text; do not suppress them.

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "Document Remote Config and Installations support"
```
