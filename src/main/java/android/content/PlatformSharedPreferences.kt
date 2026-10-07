/*
 * SharedPreferences persisted through FirebasePlatform, returned by Context.getSharedPreferences
 * for the preference files of Remote Config, Installations, and Firebase heartbeats. Each key is
 * stored as "<file>|<key>", with string sets JSON-encoded. An index under "<file>|__keys" maps each
 * key to whether its value is a string set, so getAll() can restore the type and clear() can find
 * every key. Puts are written immediately, so commit() and apply() have nothing left to do.
 */
package android.content

import com.google.firebase.FirebasePlatform
import kotlinx.serialization.builtins.MapSerializer
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

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        platform.retrieve(storageKey(key))?.let { Json.decodeFromString(stringSetSerializer, it) } ?: defValues

    override fun getAll(): Map<String, Any> = synchronized(lock) {
        index().mapNotNull { (key, isStringSet) ->
            platform.retrieve(storageKey(key))?.let { value ->
                key to if (isStringSet) Json.decodeFromString(stringSetSerializer, value) else value
            }
        }.toMap()
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

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            put(key, values?.let { Json.encodeToString(stringSetSerializer, it) }, isStringSet = true)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            put(key, null)
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

    private fun put(key: String, value: String?, isStringSet: Boolean = false) = synchronized(lock) {
        if (value == null) {
            platform.clear(storageKey(key))
            writeIndex(index() - key)
        } else {
            platform.store(storageKey(key), value)
            writeIndex(index() + (key to isStringSet))
        }
    }

    private fun clearAll() = synchronized(lock) {
        index().keys.forEach { platform.clear(storageKey(it)) }
        platform.clear(indexKey)
    }

    private fun index(): Map<String, Boolean> = platform.retrieve(indexKey)?.let { Json.decodeFromString(indexSerializer, it) } ?: emptyMap()

    private fun writeIndex(index: Map<String, Boolean>) = platform.store(indexKey, Json.encodeToString(indexSerializer, index))

    private fun storageKey(key: String) = "$name|$key"

    private val indexKey: String
        get() = "$name|__keys"

    companion object {
        private val lock = Any()
        private val stringSetSerializer = SetSerializer(String.serializer())
        private val indexSerializer = MapSerializer(String.serializer(), Boolean.serializer())
    }
}
