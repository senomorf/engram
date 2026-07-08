package cam.engram.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import cam.engram.app.ui.theme.EngramTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Composition smoke tests: each user-facing screen must compose against the real
 * app container without throwing, exercising its empty-state layout. Screens pull
 * their ViewModels from appContainer(), which resolves under Robolectric because
 * EngramApp is the test application.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenRenderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun onboardingRenders() {
        compose.setContent { EngramTheme { OnboardingScreen(onDone = {}) } }
        compose.onRoot().assertExists()
    }

    @Test
    fun settingsRenders() {
        compose.setContent { EngramTheme { SettingsScreen(onBack = {}) } }
        compose.onRoot().assertExists()
    }

    @Test
    fun toolsRenders() {
        compose.setContent { EngramTheme { ToolsScreen(onBack = {}) } }
        compose.onRoot().assertExists()
    }

    @Test
    fun browseRenders() {
        compose.setContent { EngramTheme { BrowseScreen(onOpen = {}, onBack = {}) } }
        compose.onRoot().assertExists()
    }

    @Test
    fun queueRenders() {
        compose.setContent { EngramTheme { QueueScreen(onAnnotate = { _, _ -> }, onBack = {}) } }
        compose.onRoot().assertExists()
    }

    @Test
    fun memoryDetailRenders() {
        compose.setContent { EngramTheme { MemoryDetailScreen(mediaId = 1, onAnnotate = {}, onBack = {}) } }
        compose.onRoot().assertExists()
    }

    @Test
    fun annotateRenders() {
        compose.setContent { EngramTheme { AnnotateScreen(mediaIds = listOf(1), startIndex = 0, onDone = {}) } }
        compose.onRoot().assertExists()
    }

    @Test
    fun rootRenders() {
        compose.setContent { EngramTheme { EngramRoot() } }
        compose.onRoot().assertExists()
    }
}
