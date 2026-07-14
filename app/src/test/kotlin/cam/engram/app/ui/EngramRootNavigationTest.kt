package cam.engram.app.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cam.engram.app.FakeContentAccess
import cam.engram.app.R
import cam.engram.app.ScreenTest
import cam.engram.app.fakeContainer
import cam.engram.app.grantMediaPermissions
import cam.engram.app.setScreen
import cam.engram.format.testing.SyntheticMedia
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/** Exercises the real EngramRoot gate + Navigator/MainNavigation by rendering and clicking through. */
@RunWith(RobolectricTestRunner::class)
class EngramRootNavigationTest : ScreenTest() {
    private val app = fakeContainer().closingDb()
    private val strings = ApplicationProvider.getApplicationContext<Context>()

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

    @Test
    fun finishingOnboardingRequestsNotificationPermission() {
        // fresh install: POST_NOTIFICATIONS is never granted silently on API 33+, and
        // the digest defaults on, so finishing onboarding must ask (D30)
        runBlocking { app.settings.setOnboardingDone(false) }
        compose.setScreen(app) { EngramRoot() }
        walkOnboarding()
        compose.waitForIdle()
        val intent =
            shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
        kotlin.test.assertTrue(
            intent != null && intent.action == "android.content.pm.action.REQUEST_PERMISSIONS",
            "finishing onboarding must launch the notification permission request, got $intent",
        )
        // the request never blocks entry into the app
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.home_tagline)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun grantedNotificationPermissionSkipsTheRequest() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { app.settings.setOnboardingDone(false) }
        compose.setScreen(app) { EngramRoot() }
        walkOnboarding()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.home_tagline)).fetchSemanticsNodes().isNotEmpty()
        }
        val intent =
            shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
        kotlin.test.assertTrue(
            intent == null || intent.action != "android.content.pm.action.REQUEST_PERMISSIONS",
            "an already granted permission must not be requested again",
        )
    }

    @Test
    fun startupResolvesASettleablePendingWrite() {
        // a crash left a journal whose target still equals the backup (nothing landed): startup
        // recovery settles it grant-free, so no stale backup lingers to wedge later saves (C2)
        runBlocking { app.settings.setOnboardingDone(true) }
        grantMediaPermissions()
        val bytes = SyntheticMedia.jpegPlain()
        (app.access as FakeContentAccess).files["content://media/50"] = bytes
        val backupDir =
            File(strings.filesDir, "writeback").apply {
                deleteRecursively()
                mkdirs()
            }
        File(backupDir, "50.bak").writeBytes(bytes)
        File(backupDir, "50.meta").writeText("content://media/50\nfalse\nimage/jpeg\ndeadbeef")

        compose.setScreen(app) { EngramRoot() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.home_tagline)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        kotlin.test.assertTrue(
            !File(backupDir, "50.bak").exists(),
            "startup recovery must settle a journal whose target already matches its backup",
        )
    }

    private fun walkOnboarding() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.onboard_next)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.onboard_next)).performClick()
        compose.onNodeWithText(strings.getString(R.string.onboard_next)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.onboard_start)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.onboard_start)).performClick()
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
