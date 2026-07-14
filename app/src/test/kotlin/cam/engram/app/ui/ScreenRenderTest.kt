package cam.engram.app.ui

import androidx.compose.ui.test.onRoot
import cam.engram.app.ScreenTest
import cam.engram.app.fakeContainer
import cam.engram.app.setScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Composition smoke tests: each screen must compose against a fake AppContainer (empty state)
 * without throwing. Depth (populated data + interactions) lives in the per-screen tests.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenRenderTest : ScreenTest() {
    private val app = fakeContainer().closingDb()

    @Test
    fun onboardingRenders() {
        compose.setScreen(app) { OnboardingScreen(onDone = {}) }
        compose.onRoot().assertExists()
    }

    @Test
    fun settingsRenders() {
        compose.setScreen(app) { SettingsScreen(onBack = {}) }
        compose.onRoot().assertExists()
    }

    @Test
    fun toolsRenders() {
        compose.setScreen(app) { ToolsScreen(onBack = {}) }
        compose.onRoot().assertExists()
    }

    @Test
    fun browseRenders() {
        compose.setScreen(app) { BrowseScreen(onOpen = {}, onBack = {}) }
        compose.onRoot().assertExists()
    }

    @Test
    fun queueRenders() {
        compose.setScreen(app) { QueueScreen(onAnnotate = { _, _ -> }, onBack = {}) }
        compose.onRoot().assertExists()
    }

    @Test
    fun memoryDetailRenders() {
        compose.setScreen(app) { MemoryDetailScreen(mediaId = 1, onAnnotate = {}, onBack = {}) }
        compose.onRoot().assertExists()
    }

    @Test
    fun annotateRenders() {
        compose.setScreen(app) { AnnotateScreen(mediaIds = listOf(1), startIndex = 0, onDone = {}) }
        compose.onRoot().assertExists()
    }

    @Test
    fun rootRenders() {
        compose.setScreen(app) { EngramRoot() }
        compose.onRoot().assertExists()
    }
}
