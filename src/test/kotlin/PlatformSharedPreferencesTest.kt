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
