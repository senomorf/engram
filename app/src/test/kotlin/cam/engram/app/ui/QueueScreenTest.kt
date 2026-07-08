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
import cam.engram.app.setScreen
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueueScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = fakeContainer()
    private val strings = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() = app.db.close()

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
}
