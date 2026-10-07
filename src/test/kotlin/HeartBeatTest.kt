/*
 * Tests that Firebase's heartbeat reporting (firebase-common), which Installations attaches to
 * its requests, can store and read heartbeats through the platform-backed preferences.
 */
import com.google.firebase.heartbeatinfo.DefaultHeartBeatController
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartBeatTest : FirebaseTest() {

    @Test
    fun `registered heartbeat is reported in the header`(): Unit = runTest {
        val controller = app.get(DefaultHeartBeatController::class.java)

        controller.registerHeartBeat().await()

        assertTrue(controller.heartBeatsHeader.await().isNotEmpty())
    }
}
