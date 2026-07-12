package cam.engram.app.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cam.engram.app.FakeContentAccess
import cam.engram.app.R
import cam.engram.app.fakeContainer
import cam.engram.app.grantMediaPermissions
import cam.engram.app.grantPartialMediaAccess
import cam.engram.app.seedQueue
import cam.engram.app.setScreen
import cam.engram.format.testing.SyntheticMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QueueScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = fakeContainer()
    private val strings = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        app.db.close()
    }

    @Test
    fun showsPermissionRationaleWhenMediaAccessDenied() {
        compose.setScreen(app) { QueueScreen(onAnnotate = { _, _ -> }, onBack = {}) }
        compose.onNodeWithText(strings.getString(R.string.queue_permission_rationale)).assertIsDisplayed()
    }

    // finding H5: a partial "Select photos" grant cannot drive whole-library ingestion, so the
    // queue steers toward "Allow all" instead of proceeding on an ephemeral subset
    @Test
    fun showsPartialSteerWhenOnlySomePhotosSelected() {
        grantPartialMediaAccess()
        compose.setScreen(app) { QueueScreen(onAnnotate = { _, _ -> }, onBack = {}) }
        compose.onNodeWithText(strings.getString(R.string.queue_allow_all)).assertIsDisplayed()
        compose.onNodeWithText(strings.getString(R.string.queue_partial_media_rationale)).assertIsDisplayed()
    }

    @Test
    fun showsEmptyStateWhenGrantedAndNothingWaiting() {
        grantMediaPermissions()
        compose.setScreen(app) { QueueScreen(onAnnotate = { _, _ -> }, onBack = {}) }
        // permission passes, the refresh reconciles an empty store, so the empty message shows
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.queue_empty)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.queue_empty)).assertIsDisplayed()
    }

    @Test
    fun showsQueuedItemsAfterReconcile() {
        grantMediaPermissions()
        app.seedQueue(1)
        app.seedQueue(2)
        compose.setScreen(app) { QueueScreen(onAnnotate = { _, _ -> }, onBack = {}) }
        // refresh reconciles the two waiting items in, so the empty message disappears and the grid shows
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.queue_empty)).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.queue_empty)).assertDoesNotExist()
    }

    // finding C2: a write left mid-restore by a lost grant surfaces a "finish restoring" card
    @Test
    fun showsRecoveryBannerWhenAWriteNeedsConsent() {
        grantMediaPermissions()
        val access = app.access as FakeContentAccess
        val uri = "content://media/60"
        access.files[uri] = ByteArray(3) { 0x11 } // truncated target from an interrupted save
        val backupDir =
            File(strings.filesDir, "writeback").apply {
                deleteRecursively()
                mkdirs()
            }
        File(backupDir, "60.bak").writeBytes(SyntheticMedia.jpegPlain())
        File(backupDir, "60.meta").writeText("$uri\nfalse\nimage/jpeg\ndeadbeef")
        access.rejectRestore = true // the restore needs a grant the app lacks

        compose.setScreen(app) { QueueScreen(onAnnotate = { _, _ -> }, onBack = {}) }
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithText(strings.getString(R.string.queue_recovery_restore))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.queue_recovery_restore)).assertIsDisplayed()
        // tapping requests consent (a no-op under Robolectric, where createWriteRequest is null)
        compose.onNodeWithText(strings.getString(R.string.queue_recovery_restore)).performClick()
    }
}
