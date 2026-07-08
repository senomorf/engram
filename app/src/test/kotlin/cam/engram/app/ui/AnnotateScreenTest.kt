package cam.engram.app.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import cam.engram.app.R
import cam.engram.app.fakeContainer
import cam.engram.app.seedItem
import cam.engram.app.seedMemory
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AnnotateScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = fakeContainer()
    private val strings = ApplicationProvider.getApplicationContext<Context>()

    // viewModelScope dispatches on Main; run those launches eagerly so save/onNext complete
    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        app.db.close()
    }

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

    @Test
    fun savingWritesTheNoteAndAdvances() {
        runBlocking { app.seedMemory(1, note = "existing") }
        var done = false
        compose.setScreen(app) { AnnotateScreen(mediaIds = listOf(1), startIndex = 0, onDone = { done = true }) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("a fresh memory")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.annotate_save)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.annotate_save)).performClick()
        // a successful write drives SaveUi.Saved -> onNext, which for a single item calls onDone
        compose.waitUntil(5_000) { done }
        assertTrue(done)
    }

    @Test
    fun skipAdvancesWithoutWriting() {
        runBlocking { app.seedItem(2) }
        var done = false
        compose.setScreen(app) { AnnotateScreen(mediaIds = listOf(2), startIndex = 0, onDone = { done = true }) }
        compose.onNodeWithText(strings.getString(R.string.annotate_skip)).performClick()
        compose.waitUntil(5_000) { done }
        assertTrue(done)
    }
}
