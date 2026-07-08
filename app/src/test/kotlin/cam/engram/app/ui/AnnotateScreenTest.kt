package cam.engram.app.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import cam.engram.app.R
import cam.engram.app.fakeContainer
import cam.engram.app.seedItem
import cam.engram.app.setScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnnotateScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = fakeContainer()
    private val strings = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() = app.db.close()

    @Test
    fun typingNoteShowsTextAndSaveAction() {
        runBlocking { app.seedItem(7) }
        compose.setScreen(app) { AnnotateScreen(mediaIds = listOf(7), startIndex = 0, onDone = {}) }
        // the note field appears once the item loads; type into it
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("moonlight over the bay")
        compose.onNodeWithText("moonlight over the bay").assertIsDisplayed()
        // once there is content the button switches from Done to "Save into the photo"
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.annotate_save)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.annotate_save)).assertIsDisplayed()
    }
}
