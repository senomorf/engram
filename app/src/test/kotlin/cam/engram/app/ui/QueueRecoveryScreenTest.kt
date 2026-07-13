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

/**
 * The recovery-banner assertion drives the whole async LaunchedEffect -> refresh -> recoverPending ->
 * StateFlow -> recompose chain, the most timing-fragile screen check. It is 100% green run on its own
 * but flakes ~50% when other QueueScreen compositions run before it in the same JVM: the accumulated
 * Compose/coroutine state starves its recompose (the setScreen ViewModelStore cleanup helps the
 * milder QueueScreen assertions but not this one). Kept alone in its own class so it never runs after
 * a sibling QueueScreen composition.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QueueRecoveryScreenTest {
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
