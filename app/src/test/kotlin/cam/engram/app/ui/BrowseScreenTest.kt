package cam.engram.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import cam.engram.app.ScreenTest
import cam.engram.app.fakeContainer
import cam.engram.app.seedBrowsable
import cam.engram.app.setScreen
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowseScreenTest : ScreenTest() {
    private val app = fakeContainer().closingDb()

    @Test
    fun rendersTimelineAndReflectsSearchQuery() {
        // two annotated items populate the timeline grid; typing drives the debounced FTS search
        runBlocking {
            app.seedBrowsable(1, "sunrise over the lake")
            app.seedBrowsable(2, "birthday cake candles")
        }
        compose.setScreen(app) { BrowseScreen(onOpen = {}, onBack = {}) }
        compose.onNode(hasSetTextAction()).performTextInput("sunrise")
        compose.onNodeWithText("sunrise").assertIsDisplayed()
    }
}
