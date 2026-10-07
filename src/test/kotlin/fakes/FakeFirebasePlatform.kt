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
