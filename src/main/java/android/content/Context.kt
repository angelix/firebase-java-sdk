package android.content

import android.content.SharedPreferences.Editor
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.ConnectivityManager
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import com.google.firebase.FirebasePlatform
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLEncoder

open class Context {

    val applicationContext: Context
        get() = this

    val mainLooper: Looper
        get() = Looper.getMainLooper()

    val packageName: String
        get() = "app.teamhub.TeamHub"

    val resources: Resources
        get() = Resources()

    val packageManager: PackageManager
        get() = PackageManager()

    val isDeviceProtectedStorage: Boolean
        get() = false

    val noBackupFilesDir: File
        get() = File(System.getProperty("java.io.tmpdir"))

    val classLoader: ClassLoader
        get() = ClassLoader.getSystemClassLoader()

    val contentResolver: ContentResolver
        get() = ContentResolver()

    val applicationInfo: ApplicationInfo = ApplicationInfo()

    fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        if (name.startsWith("frc_") || name == "com.google.android.gms.appid" || name.startsWith("FirebaseHeartBeat")) {
            return PlatformSharedPreferences(name)
        }
        return object : SharedPreferences {
            override fun contains(key: String): Boolean {
                when (key) {
                    "firebase_data_collection_default_enabled" -> return false
                    "auto_init" -> return false
                    "firebase_messaging_auto_init_enabled" -> return false
                    "fire-fst" -> return false
                    "fire-global" -> return !FirebasePlatform.firebasePlatform.retrieve(key).isNullOrEmpty()
                }
                throw IllegalArgumentException(key)
            }

            override fun getString(key: String, defaultValue: String?): String? {
                return when {
                    key == "last-used-date" -> FirebasePlatform.firebasePlatform.retrieve(key) ?: defaultValue
                    key.contains("|T|") -> null
                    key.startsWith("com.google.firebase.auth.FIREBASE_USER") ->
                        FirebasePlatform.firebasePlatform.retrieve(key) ?: defaultValue
                    else -> throw IllegalArgumentException(key)
                }
            }

            override fun getLong(key: String?, defaultValue: Long): Long {
                when (key) {
                    "fire-global" -> return FirebasePlatform.firebasePlatform.retrieve(key)?.toLong() ?: defaultValue
                }
                throw IllegalArgumentException(key)
            }

            override fun getInt(key: String?, defaultValue: Int): Int {
                throw IllegalArgumentException(key)
            }

            override fun getStringSet(key: String?, defaultValues: Set<String>?): Set<String>? {
                throw IllegalArgumentException(key)
            }

            override fun getAll(): Map<String, String> {
                return emptyMap()
            }

            override fun edit(): Editor {
                return object : Editor {
                    override fun putLong(key: String?, value: Long): Editor {
                        when (key) {
                            "fire-global" -> FirebasePlatform.firebasePlatform.store(key, value.toString())
                            else -> throw IllegalArgumentException(key)
                        }
                        return this
                    }

                    override fun putString(key: String?, value: String?): Editor {
                        when (key) {
                            "last-used-date" -> FirebasePlatform.firebasePlatform.store(key, value.toString())
                            else -> if (key?.startsWith("com.google.firebase.auth.FIREBASE_USER") == true) {
                                FirebasePlatform.firebasePlatform.store(key, value.toString())
                            } else {
                                throw IllegalArgumentException(key)
                            }
                        }
                        return this
                    }

                    override fun putInt(key: String?, value: Int): Editor {
                        throw IllegalArgumentException(key)
                    }

                    override fun putStringSet(key: String?, values: Set<String>?): Editor {
                        throw IllegalArgumentException(key)
                    }

                    override fun remove(key: String?): Editor {
                        throw IllegalArgumentException(key)
                    }

                    override fun clear(): Editor {
                        throw IllegalArgumentException(name)
                    }

                    override fun commit(): Boolean {
                        // Don't need to commit as changes are committed in the put method
                        return true
                    }

                    override fun apply() {
                        // Don't need to apply as changes are applied in the put method
                    }
                }
            }
        }
    }

    fun getSystemService(name: String): Any {
        when (name) {
            "power" -> return PowerManager()
            CONNECTIVITY_SERVICE -> return ConnectivityManager.instance
        }
        throw IllegalArgumentException(name)
    }

    fun getSystemService(clazz: Class<*>): Any {
        when (clazz) {
            UserManager::class.java -> return UserManager()
        }
        throw IllegalArgumentException(clazz.toString())
    }

    fun getDir(path: String, flags: Int): File {
        return File(System.getProperty("java.io.tmpdir"))
    }

    fun getDatabasePath(name: String): File = FirebasePlatform.firebasePlatform.getDatabasePath(name)

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

    companion object {
        @JvmStatic
        val CONNECTIVITY_SERVICE = "connectivity"

        const val MODE_APPEND = 0x8000
    }
}
