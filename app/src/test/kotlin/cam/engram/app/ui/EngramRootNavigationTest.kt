package cam.engram.app.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cam.engram.app.R
import cam.engram.app.fakeContainer
import cam.engram.app.grantMediaPermissions
import cam.engram.app.setScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Exercises the real EngramRoot gate + Navigator/MainNavigation by rendering and clicking through. */
@RunWith(RobolectricTestRunner::class)
class EngramRootNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = fakeContainer()
    private val strings = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() = app.db.close()

    @Test
    fun navigatesFromHomeToQueue() {
        runBlocking { app.settings.setOnboardingDone(true) }
        grantMediaPermissions()
        compose.setScreen(app) { EngramRoot() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.home_tagline)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.open_queue)).performClick()
        // pushed onto the Queue screen, which (permissions granted, empty store) shows its empty state
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.queue_empty)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.queue_empty)).assertIsDisplayed()
    }

    @Test
    fun showsOnboardingUntilDone() {
        runBlocking { app.settings.setOnboardingDone(false) }
        compose.setScreen(app) { EngramRoot() }
        compose.waitForIdle()
        // the onboarding gate keeps the home content off screen until onboarding completes
        compose.onNodeWithText(strings.getString(R.string.home_tagline)).assertDoesNotExist()
    }
}
