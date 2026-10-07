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
