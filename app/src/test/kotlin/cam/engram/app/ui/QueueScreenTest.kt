package cam.engram.app.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import cam.engram.app.R
import cam.engram.app.fakeContainer
import cam.engram.app.grantMediaPermissions
import cam.engram.app.seedQueue
import cam.engram.app.setScreen
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
}
