# Preferences Stored as Files Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store every `SharedPreferences` file as a JSON file under `getFilesDir()/shared_prefs`, replacing the `FirebasePlatform`-backed preferences and the strict whitelist, and fix the test hygiene and documentation findings from the PR #70 review.

**Architecture:** `PreferencesFile` (new) implements `SharedPreferences` with one shared instance per file path, values held in memory and written atomically on commit. `Context.getSharedPreferences` maps a name to `getFilesDir()/shared_prefs/<URL-encoded name>.json` and returns that instance for every name. Auth keeps writing straight to `FirebasePlatform.store`.

**Tech Stack:** Kotlin 2.0.20 / Java 17, Gradle 8.13, JUnit 4, kotlinx-serialization-json 1.7.2 (already an `implementation` dependency), kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-10-07-preferences-storage-design.md`

## Global Constraints

- Run every Gradle command with `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr`.
- Run the full suite inside the Auth emulator: `firebase emulators:exec --project my-firebase-project --import=src/test/resources/firebase_data '<gradle command>'`. Single test classes may run without it: `./gradlew test --tests '<Class>'`; read failures with `grep -A8 '<failure' build/test-results/test/TEST-<Class>.xml`.
- `ktlintCheck` must pass (ktlint 0.47.1). No trailing commas. Formatting-only failures: run `./gradlew ktlintFormat`; never hand-edit whitespace.
- Every new file starts with a brief header comment stating its purpose and how it fits the project.
- Match the style of the file you edit. Add no dependencies.
- Commit messages carry no AI attribution.
- Auth is unchanged: it writes the signed-in user with `FirebasePlatform.store`.
- `getFilesDir()`'s default and the URL-encoding of file names are unchanged.
- No migration of the bare `fire-global` / `last-used-date` keys earlier versions left in users' stores.
- If a test fails for a reason this plan does not predict, stop, find the root cause, report it, and wait for Angelos.
- Design docs (`docs/superpowers/`) are committed during the work and removed in one commit before pushing.

## Review Focus

1. **A write fails** (full disk, read-only folder): `commit()` returns `false`, the previous values stay readable, and the failure is logged. Task 1, `failed commit keeps previous values and returns false`.
2. **Concurrent commits** from Remote Config, Installations and heartbeat executors must all reach the file. Task 1, `concurrent commits all land in the file`.
3. **A corrupt or foreign file** on disk must not crash Firebase: it is logged, read as empty, and replaced on the next commit. Task 1, `corrupt file is logged, read as empty and overwritten`.
4. **Callers that mutate a returned string set** (heartbeat code edits sets it reads) must not change stored values without a commit. Task 1, `changing a returned string set does not change the stored value`.
5. **Restart:** values written before a restart are read back from disk with their types. Task 1, `existing file is loaded when first opened`.

---

### Task 1: `PreferencesFile` becomes the preferences for every name

**Files:**
- Create: `src/main/java/android/content/PreferencesFile.kt`
- Modify: `src/main/java/android/content/SharedPreferences.java`
- Modify: `src/main/java/android/content/Context.kt` (`getSharedPreferences`, `fileStreamPath`, imports)
- Delete: `src/main/java/android/content/PlatformSharedPreferences.kt`, `src/test/kotlin/PlatformSharedPreferencesTest.kt`
- Modify: `src/test/kotlin/fakes/FakeFirebasePlatform.kt`, `src/test/kotlin/FirebaseTest.kt`, `src/test/kotlin/ContextFilesTest.kt`
- Test: `src/test/kotlin/PreferencesFileTest.kt` (create)

**Interfaces:**
- Produces: `internal class PreferencesFile : SharedPreferences` with `companion fun at(file: File): PreferencesFile` (one instance per absolute path); `SharedPreferences.getBoolean(String, boolean)`, `Editor.putBoolean(String, boolean)`; `FakeFirebasePlatform.logs: MutableList<String>` (captured, not printed), `FakeFirebasePlatform.storage` defaults to a `ConcurrentHashMap`; `FirebaseTest.folder: TemporaryFolder` with `getFilesDir() = File(folder.root, "files")`.
- On-disk format: a JSON object mapping each key to a one-entry object naming its type: `{"string": "…"}`, `{"int": 3}`, `{"long": 42}`, `{"boolean": true}`, `{"stringSet": ["…"]}`.

- [ ] **Step 1: Capture logs and use a thread-safe store in `FakeFirebasePlatform`**

Replace `src/test/kotlin/fakes/FakeFirebasePlatform.kt` body so it reads:

```kotlin
package fakes

import com.google.firebase.FirebasePlatform
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Fake used to store firebase data during testing. The [storage] is made purposefully public to allow for direct
 * access and modification if needed.
 */
class FakeFirebasePlatform(
    val storage: MutableMap<String, String> = ConcurrentHashMap(),
    databaseFolderPath: String = "./build/database/",
    filesFolderPath: String = "./build/files/"
) : FirebasePlatform() {

    private val databaseFolder = File(databaseFolderPath)

    private val filesFolder = File(filesFolderPath)

    // Captured instead of printed, so tests can assert on expected log output
    val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun store(key: String, value: String) { storage[key] = value }

    override fun retrieve(key: String) = storage[key]

    override fun clear(key: String) { storage.remove(key) }

    override fun log(msg: String) { logs.add(msg) }

    override fun getDatabasePath(name: String) = File(databaseFolder, name)

    override fun getFilesDir() = filesFolder
}
```

- [ ] **Step 2: Write the failing `PreferencesFile` tests**

Create `src/test/kotlin/PreferencesFileTest.kt`:

```kotlin
/*
 * Tests for PreferencesFile, the SharedPreferences that stores each preferences name as a JSON
 * file under FirebasePlatform.getFilesDir()/shared_prefs.
 */
import android.content.PreferencesFile
import com.google.firebase.FirebasePlatform
import fakes.FakeFirebasePlatform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.concurrent.thread

class PreferencesFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val platform = FakeFirebasePlatform()

    private fun file(name: String) = File(folder.root, "shared_prefs/$name.json")

    private fun prefs(name: String = "frc_app_firebase_settings") = PreferencesFile.at(file(name))

    @Before
    fun setUp() {
        FirebasePlatform.initializeFirebasePlatform(platform)
    }

    @Test
    fun `values round-trip with their types`() {
        val prefs = prefs()
        prefs.edit()
            .putString("string", "abc")
            .putInt("int", -1)
            .putLong("long", 42L)
            .putBoolean("boolean", true)
            .putStringSet("stringSet", setOf("x", "y"))
            .commit()

        assertEquals("abc", prefs.getString("string", null))
        assertEquals(-1, prefs.getInt("int", 0))
        assertEquals(42L, prefs.getLong("long", 0L))
        assertTrue(prefs.getBoolean("boolean", false))
        assertEquals(setOf("x", "y"), prefs.getStringSet("stringSet", null))
        assertEquals(
            mapOf("string" to "abc", "int" to -1, "long" to 42L, "boolean" to true, "stringSet" to setOf("x", "y")),
            prefs.all
        )
    }

    @Test
    fun `missing keys return defaults`() {
        val prefs = prefs()

        assertEquals("default", prefs.getString("missing", "default"))
        assertEquals(7, prefs.getInt("missing", 7))
        assertEquals(7L, prefs.getLong("missing", 7L))
        assertTrue(prefs.getBoolean("missing", true))
        assertEquals(setOf("default"), prefs.getStringSet("missing", setOf("default")))
        assertFalse(prefs.contains("missing"))
    }

    @Test
    fun `remove and putString null delete the key`() {
        val prefs = prefs()
        prefs.edit().putString("first", "a").putString("second", "b").commit()

        prefs.edit().remove("first").putString("second", null).commit()

        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `files do not see each other's keys`() {
        prefs("first").edit().putString("key", "first").commit()

        assertNull(prefs("second").getString("key", null))
    }

    @Test
    fun `clear affects only its own file`() {
        prefs("first").edit().putString("key", "first").commit()
        prefs("second").edit().putString("key", "second").commit()

        prefs("first").edit().clear().commit()

        assertTrue(prefs("first").all.isEmpty())
        assertEquals("second", prefs("second").getString("key", null))
    }

    @Test
    fun `clear applies before the edits it is batched with`() {
        val prefs = prefs()
        prefs.edit().putString("old", "value").commit()

        prefs.edit().putString("kept", "value").clear().commit()

        assertEquals(mapOf("kept" to "value"), prefs.all)
    }

    @Test
    fun `edits are not visible before commit`() {
        val prefs = prefs()
        val edit = prefs.edit().putString("key", "value")

        assertFalse(prefs.contains("key"))
        edit.commit()
        assertTrue(prefs.contains("key"))
    }

    @Test
    fun `commit writes json and leaves no temporary files`() {
        prefs().edit().putInt("num_failed_fetches", 3).commit()

        val stored = Json.parseToJsonElement(file("frc_app_firebase_settings").readText()).jsonObject
        assertEquals(setOf("num_failed_fetches"), stored.keys)
        assertEquals(listOf("frc_app_firebase_settings.json"), file("frc_app_firebase_settings").parentFile.list()!!.toList())
    }

    @Test
    fun `existing file is loaded when first opened`() {
        file("restored").parentFile.mkdirs()
        file("restored").writeText(
            """{"num_failed_fetches":{"int":3},"last_fetch_time_in_millis":{"long":42},"fire-core":{"stringSet":["2026-10-08"]}}"""
        )

        val prefs = prefs("restored")

        assertEquals(3, prefs.getInt("num_failed_fetches", 0))
        assertEquals(42L, prefs.getLong("last_fetch_time_in_millis", 0L))
        assertEquals(setOf("2026-10-08"), prefs.getStringSet("fire-core", null))
    }

    @Test
    fun `missing file reads as empty`() {
        val prefs = prefs("absent")

        assertTrue(prefs.all.isEmpty())
        assertFalse(file("absent").exists())
    }

    @Test
    fun `corrupt file is logged, read as empty and overwritten`() {
        file("corrupt").parentFile.mkdirs()
        file("corrupt").writeText("not json")

        val prefs = prefs("corrupt")

        assertTrue(prefs.all.isEmpty())
        assertTrue(platform.logs.any { it.startsWith("PreferencesFile Ignoring unreadable preferences file") })
        prefs.edit().putString("key", "value").commit()
        assertEquals(setOf("key"), Json.parseToJsonElement(file("corrupt").readText()).jsonObject.keys)
    }

    @Test
    fun `failed commit keeps previous values and returns false`() {
        val prefs = prefs()
        prefs.edit().putString("key", "before").commit()
        val sharedPrefs = file("frc_app_firebase_settings").parentFile
        sharedPrefs.setWritable(false)
        try {
            assertFalse(prefs.edit().putString("key", "after").commit())
            assertEquals("before", prefs.getString("key", null))
            assertTrue(platform.logs.any { it.startsWith("PreferencesFile Failed to write preferences file") })
        } finally {
            sharedPrefs.setWritable(true)
        }
    }

    @Test
    fun `concurrent commits all land in the file`() {
        val prefs = prefs()
        (0 until 8).map { i ->
            thread { repeat(50) { j -> prefs.edit().putInt("key-$i-$j", j).commit() } }
        }.forEach { it.join() }

        assertEquals(400, prefs.all.size)
        assertEquals(400, Json.parseToJsonElement(file("frc_app_firebase_settings").readText()).jsonObject.size)
    }

    @Test
    fun `changing a returned string set does not change the stored value`() {
        val prefs = prefs()
        prefs.edit().putStringSet("dates", setOf("2026-10-08")).commit()

        (prefs.getStringSet("dates", null) as MutableSet<String>).add("2026-10-09")
        (prefs.all["dates"] as MutableSet<String>).add("2026-10-10")

        assertEquals(setOf("2026-10-08"), prefs.getStringSet("dates", null))
    }

    @Test
    fun `same path returns the same instance`() {
        assertSame(prefs("shared"), prefs("shared"))
    }
}
```

- [ ] **Step 3: Write the failing `Context` tests**

Append to `ContextFilesTest` (before its closing brace):

```kotlin
    @Test
    fun `preferences are stored as files in filesDir`() {
        context.getSharedPreferences("frc_1:341458593155:web:bf8e1aa37efe01f32d42b6_firebase_settings", 0)
            .edit().putLong("last_fetch_time_in_millis", 1L).commit()

        val stored = File(filesDir, "shared_prefs").list()!!.single()
        assertTrue(stored.endsWith(".json"))
        assertTrue(':' !in stored)
    }

    @Test
    fun `any preferences name is accepted`() {
        val prefs = context.getSharedPreferences("com.google.firebase.common.prefs:W0RFRkFVTFRd", 0)

        assertTrue(prefs.getBoolean("firebase_data_collection_default_enabled", true))
    }
```

- [ ] **Step 4: Run the tests and confirm they fail**

Run: `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr ./gradlew test --tests 'PreferencesFileTest' --tests 'ContextFilesTest'`
Expected: test compilation fails with unresolved `PreferencesFile`, `putBoolean`, and `getBoolean`.

- [ ] **Step 5: Add the boolean methods to the interface**

In `src/main/java/android/content/SharedPreferences.java`, after `int getInt(String key, int defValue);` add `boolean getBoolean(String key, boolean defValue);`, and in `Editor` after `Editor putInt(String key, int value);` add `Editor putBoolean(String key, boolean value);`.

- [ ] **Step 6: Create `PreferencesFile`**

Create `src/main/java/android/content/PreferencesFile.kt`:

```kotlin
/*
 * SharedPreferences stored as one JSON file per preferences name under
 * FirebasePlatform.getFilesDir()/shared_prefs, as Android stores them in the app's data folder.
 * Keeping preferences beside the other files Firebase writes to getFilesDir() means they are kept
 * or lost together. Every caller of a file path shares one instance, which holds the values in
 * memory and replaces the whole file atomically on commit.
 */
package android.content

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

internal class PreferencesFile private constructor(private val file: File) : SharedPreferences {

    // Loaded from the file on first use; read and replaced only while holding this instance's lock
    private var loadedValues: Map<String, Any>? = null

    override fun contains(key: String): Boolean = values().containsKey(key)

    override fun getString(key: String, defaultValue: String?): String? = values()[key] as String? ?: defaultValue

    override fun getInt(key: String, defValue: Int): Int = values()[key] as Int? ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values()[key] as Long? ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = values()[key] as Boolean? ?: defValue

    // Returns a copy, so callers cannot change the stored set without an edit
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (values()[key] as Set<*>?)?.mapTo(HashSet()) { it as String } ?: defValues

    override fun getAll(): Map<String, Any> =
        values().mapValues { (_, value) -> if (value is Set<*>) HashSet(value) else value }

    override fun edit(): SharedPreferences.Editor = Edit()

    private fun values(): Map<String, Any> = synchronized(this) {
        loadedValues ?: read().also { loadedValues = it }
    }

    private fun read(): Map<String, Any> {
        if (!file.exists()) return emptyMap()
        return try {
            Json.parseToJsonElement(file.readText()).jsonObject.mapValues { (_, value) -> decode(value) }
        } catch (e: Exception) {
            // Unreadable content is discarded; the next commit replaces the file
            Log.w(TAG, "Ignoring unreadable preferences file $file", e)
            emptyMap()
        }
    }

    private fun save(changes: Map<String, Any?>, clear: Boolean): Boolean {
        synchronized(this) {
            val updated = if (clear) mutableMapOf() else values().toMutableMap()
            changes.forEach { (key, value) -> if (value == null) updated.remove(key) else updated[key] = value }
            try {
                write(updated)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write preferences file $file", e)
                return false
            }
            loadedValues = updated
            return true
        }
    }

    // Writes a temporary file and moves it over the target, so a failed write leaves the previous file intact
    private fun write(values: Map<String, Any>) {
        val folder = file.parentFile
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create $folder")
        val temporary = File.createTempFile(file.name, ".tmp", folder)
        try {
            temporary.writeText(JsonObject(values.mapValues { (_, value) -> encode(value) }).toString())
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    // Collects changes until commit() or apply(), as Android's editor does
    private inner class Edit : SharedPreferences.Editor {

        // A null value removes the key
        private val changes = mutableMapOf<String, Any?>()

        private var clearRequested = false

        override fun putString(key: String, value: String?) = change(key, value)

        override fun putInt(key: String, value: Int) = change(key, value)

        override fun putLong(key: String, value: Long) = change(key, value)

        override fun putBoolean(key: String, value: Boolean) = change(key, value)

        override fun putStringSet(key: String, values: Set<String>?) = change(key, values?.toHashSet())

        override fun remove(key: String) = change(key, null)

        override fun clear(): SharedPreferences.Editor = synchronized(this) {
            clearRequested = true
            this
        }

        override fun commit(): Boolean = synchronized(this) {
            save(changes.toMap(), clearRequested).also {
                changes.clear()
                clearRequested = false
            }
        }

        override fun apply() {
            commit()
        }

        private fun change(key: String, value: Any?): SharedPreferences.Editor = synchronized(this) {
            changes[key] = value
            this
        }
    }

    companion object {
        private const val TAG = "PreferencesFile"

        private val instances = ConcurrentHashMap<File, PreferencesFile>()

        fun at(file: File): PreferencesFile = instances.computeIfAbsent(file.absoluteFile) { PreferencesFile(it) }

        // Each value is a one-entry object naming its type, e.g. {"int": 3}, so reads restore the type
        private fun encode(value: Any): JsonElement = buildJsonObject {
            when (value) {
                is String -> put("string", value)
                is Int -> put("int", value)
                is Long -> put("long", value)
                is Boolean -> put("boolean", value)
                is Set<*> -> putJsonArray("stringSet") { value.forEach { add(it as String) } }
                else -> throw IllegalArgumentException("Unsupported preference value $value")
            }
        }

        private fun decode(element: JsonElement): Any {
            val (type, value) = element.jsonObject.entries.single()
            return when (type) {
                "string" -> value.jsonPrimitive.content
                "int" -> value.jsonPrimitive.int
                "long" -> value.jsonPrimitive.long
                "boolean" -> value.jsonPrimitive.boolean
                "stringSet" -> value.jsonArray.mapTo(HashSet()) { it.jsonPrimitive.content }
                else -> throw IllegalArgumentException("Unknown preference type $type")
            }
        }
    }
}
```

- [ ] **Step 7: Return `PreferencesFile` for every name and delete the strict whitelist**

In `src/main/java/android/content/Context.kt`:

- Replace the whole `getSharedPreferences` function (the routing `if` and the anonymous strict `SharedPreferences`) with:

```kotlin
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        PreferencesFile.at(File(File(filesDir, "shared_prefs"), "${encodeFileName(name)}.json"))
```

- Replace `fileStreamPath` and its comment with:

```kotlin
    private fun fileStreamPath(name: String): File {
        require('/' !in name && File.separatorChar !in name) { "File $name contains a path separator" }
        return File(filesDir, encodeFileName(name))
    }

    // URL-encodes the name so characters such as ':' in Firebase app IDs are valid on every OS
    private fun encodeFileName(name: String): String = URLEncoder.encode(name, Charsets.UTF_8)
```

- Remove the now-unused import `android.content.SharedPreferences.Editor`.

- [ ] **Step 8: Delete the `FirebasePlatform`-backed preferences**

```bash
git rm src/main/java/android/content/PlatformSharedPreferences.kt src/test/kotlin/PlatformSharedPreferencesTest.kt
```

- [ ] **Step 9: Isolate files per test in `FirebaseTest`**

In `src/test/kotlin/FirebaseTest.kt`: add imports `org.junit.Rule`, `org.junit.rules.TemporaryFolder`, `java.util.concurrent.ConcurrentHashMap`; add before `log`:

```kotlin
    // Preferences and config files are written under getFilesDir(), so each test gets its own
    @get:Rule
    val folder = TemporaryFolder()
```

change `val storage = mutableMapOf<String, String>()` to `val storage = ConcurrentHashMap<String, String>()`, and change `override fun getFilesDir() = File("./build/files")` to `override fun getFilesDir() = File(folder.root, "files")`.

- [ ] **Step 10: Run the tests and confirm they pass**

Run: `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr ./gradlew cleanTest test --tests 'PreferencesFileTest' --tests 'ContextFilesTest' --tests 'HeartBeatTest' --tests 'FirebaseRemoteConfigTest' --tests 'JsonReaderTest' --tests 'DateUtilsTest' ktlintCheck`
Expected: PreferencesFileTest 15, ContextFilesTest 9, HeartBeatTest 1, FirebaseRemoteConfigTest 9 (2 skipped), JsonReaderTest 1, DateUtilsTest 2 pass; ktlint clean; `<system-out>` of `TEST-PreferencesFileTest.xml`, `TEST-ContextFilesTest.xml` and `TEST-FirebaseRemoteConfigTest.xml` empty.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/android/content/PreferencesFile.kt src/main/java/android/content/SharedPreferences.java src/main/java/android/content/Context.kt src/test/kotlin/fakes/FakeFirebasePlatform.kt src/test/kotlin/FirebaseTest.kt src/test/kotlin/ContextFilesTest.kt src/test/kotlin/PreferencesFileTest.kt
git commit -m "Store preferences as JSON files in getFilesDir"
```

---

### Task 2: Documentation, AOSP headers, and the Storage test

**Files:**
- Modify: `README.md`
- Modify: `src/main/java/android/util/JsonReader.java`, `JsonScope.java`, `JsonToken.java`, `MalformedJsonException.java`, `Base64DataException.java`, `src/main/java/com/android/internal/util/StringPool.java`
- Modify: `src/test/kotlin/FirebaseStorageTest.kt`

**Interfaces:**
- Consumes: Task 1's file-backed preferences (the README describes them).

- [ ] **Step 1: Add header comments to the AOSP ports**

Insert as the first lines of each file, above the existing license comment:

`JsonReader.java`:
```java
/*
 * Ported unchanged from AOSP (android14-release). Firebase Installations parses its REST
 * responses with android.util.JsonReader.
 */
```

`JsonScope.java`, `JsonToken.java`, `MalformedJsonException.java`:
```java
/*
 * Ported unchanged from AOSP (android14-release). Part of the android.util.JsonReader port,
 * which Firebase Installations uses to parse its REST responses.
 */
```

`com/android/internal/util/StringPool.java`:
```java
/*
 * Ported unchanged from AOSP (android14-release). Used by the android.util.JsonReader port.
 */
```

`Base64DataException.java`:
```java
/*
 * Ported unchanged from AOSP (android14-release). Thrown by the android.util.Base64OutputStream
 * shim, which firebase-common uses to encode heartbeat headers.
 */
```

- [ ] **Step 2: Update the README**

Replace the paragraph starting "The Firebase products call these methods from background threads" with:

```markdown
Firebase Auth calls these methods from background threads, so your implementation must be thread-safe. The other Firebase
products keep their state in files under `getFilesDir()`, described below.
```

Replace "This is used by Remote Config and Installations to persist fetched configs and the installation ID." with:

```markdown
This is used by Remote Config and Installations to persist fetched configs and the installation ID, and by all Firebase
products to store their preferences, as JSON files in a `shared_prefs` subfolder.
```

- [ ] **Step 3: Make the Storage test wait for its request**

In `src/test/kotlin/FirebaseStorageTest.kt`, add imports `kotlinx.coroutines.tasks.await` and `kotlinx.coroutines.test.runTest`, and change `getting child reference` to:

```kotlin
    @Test
    fun `getting child reference`(): Unit = runTest {
        val storage = Firebase.storage(app)
        val reference = storage.reference
        val downloadRef = reference.child("mountains.jpg")
        val downloadUrl = downloadRef.downloadUrl

        Assert.assertNotNull(downloadUrl)
        // Waits for the request so its logs stay within this test; the outcome is not under test
        runCatching { downloadUrl.await() }
    }
```

- [ ] **Step 4: Run the full suite the way CI does**

Run:
```bash
JAVA_HOME=~/.sdkman/candidates/java/17.0.14-jbr firebase emulators:exec --project my-firebase-project --import=src/test/resources/firebase_data './gradlew cleanTest build'
cat build/test-results/test/*.xml | grep -oE 'testsuite name="[^"]*" tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"'
```
Expected: `BUILD SUCCESSFUL`; 49 tests (12 baseline, ContextFilesTest 9, PreferencesFileTest 15, JsonReaderTest 1, DateUtilsTest 2, HeartBeatTest 1, FirebaseRemoteConfigTest 9), 2 skipped, 0 failures.

- [ ] **Step 5: Check output is clean**

Read every `<system-out>`/`<system-err>` block in `build/test-results/test/*.xml`. Expected: Storage `NetworkRequest`/`StorageUtil` lines appear only in `TEST-FirebaseStorageTest.xml`; `FirebaseFirestoreTest`'s Play-services and SQLite lines are pre-existing (present on master 9f3a1b8); nothing else. Report any other output to Angelos.

- [ ] **Step 6: Commit**

```bash
git add README.md src/main/java/android/util/JsonReader.java src/main/java/android/util/JsonScope.java src/main/java/android/util/JsonToken.java src/main/java/android/util/MalformedJsonException.java src/main/java/android/util/Base64DataException.java src/main/java/com/android/internal/util/StringPool.java src/test/kotlin/FirebaseStorageTest.kt
git commit -m "Document file-based preferences, label AOSP ports, and await the Storage test request"
```
