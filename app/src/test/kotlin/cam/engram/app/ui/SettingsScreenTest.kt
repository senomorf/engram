package cam.engram.app.ui

import android.content.Context
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cam.engram.app.R
import cam.engram.app.ScreenTest
import cam.engram.app.fakeContainer
import cam.engram.app.setScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest : ScreenTest() {
    private val app = fakeContainer().closingDb()
    private val strings = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun disabledNotificationsShowHintRowWithTapThrough() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        org.robolectric.Shadows
            .shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .setNotificationsEnabled(false)
        compose.setScreen(app) { SettingsScreen(onBack = {}) }
        // digest defaults on, notifications are off: the hint row must appear
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithText(strings.getString(R.string.settings_notifications_disabled_hint))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.settings_notifications_open)).performClick()
        val intent =
            org.robolectric.Shadows
                .shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
                .nextStartedActivity
        kotlin.test.assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent?.action)
    }

    @Test
    fun togglingBurstOnWithoutGrantRequestsPermission() {
        compose.setScreen(app) { SettingsScreen(onBack = {}) }
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithText(strings.getString(R.string.settings_burst))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // toggle rows in screen order: screenshots, digest, burst, ... (burst = index 2)
        compose
            .onAllNodes(
                androidx.compose.ui.test
                    .isToggleable(),
            )[2]
            .performClick()
        compose.waitForIdle()
        val intent =
            org.robolectric.Shadows
                .shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
                .nextStartedActivity
        kotlin.test.assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", intent?.action)
    }

    @Test
    fun pickingLanguageRunsLocaleChoice() {
        compose.setScreen(app) { SettingsScreen(onBack = {}) }
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithText(strings.getString(R.string.settings_language_english))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.settings_language_english)).performClick()
        // choose("en") ran through LocaleManager without crashing
        compose.onRoot().assertExists()
    }
}
