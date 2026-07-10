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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Exercises the real EngramRoot gate + Navigator/MainNavigation by rendering and clicking through. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EngramRootNavigationTest {
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

    // mark by the first setting (top of the scrollable screen), so adding settings later
    // does not push the marker below the test viewport
    @Test
    fun navigatesToSettings() = home { R.string.open_settings to R.string.settings_screenshots }

    @Test
    fun navigatesToTools() = home { R.string.open_tools to R.string.tools_export_button }

    @Test
    fun navigatesToBrowse() = home { R.string.open_browse to R.string.open_browse }

    // reach Home, click the given entry button, assert the destination's marker text appears
    private fun home(route: () -> Pair<Int, Int>) {
        val (button, marker) = route()
        runBlocking { app.settings.setOnboardingDone(true) }
        compose.setScreen(app) { EngramRoot() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.home_tagline)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(button)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(marker)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(marker)).assertIsDisplayed()
    }
}
