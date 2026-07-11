package cam.engram.app.ui

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import cam.engram.app.R
import cam.engram.app.data.db.DraftEntity
import cam.engram.app.fakeContainer
import cam.engram.app.seedItem
import cam.engram.app.seedMemory
import cam.engram.app.setScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
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
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        // most tests exercise the granted-location path (the common case); the denial
        // path is covered by savingImageWithoutLocationWarnsFirstThenSaves
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

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
    fun overSoftCapSaveStillAdvances() {
        // an audio clip past the ~10MB soft cap drives the over-cap Saved branch (and its Toast)
        val bigAudio = File.createTempFile("big", ".ogg").apply { writeBytes(ByteArray(11 * 1024 * 1024)) }
        runBlocking {
            app.seedMemory(11, note = "seed")
            app.db.drafts().upsert(
                DraftEntity(mediaId = 11, text = "note", audioPath = bigAudio.absolutePath, updatedMillis = 1),
            )
        }
        var done = false
        compose.setScreen(app) { AnnotateScreen(mediaIds = listOf(11), startIndex = 0, onDone = { done = true }) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(strings.getString(R.string.annotate_save)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.annotate_save)).performClick()
        compose.waitUntil(10_000) { done }
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

    @Test
    fun showsAudioChipWhenDraftHasRecording() {
        val ogg = File.createTempFile("draft-audio", ".ogg").apply { writeBytes(ByteArray(64) { 1 }) }
        runBlocking {
            app.seedItem(3)
            app.db.drafts().upsert(
                DraftEntity(mediaId = 3, text = null, audioPath = ogg.absolutePath, updatedMillis = 1),
            )
        }
        compose.setScreen(app) { AnnotateScreen(mediaIds = listOf(3), startIndex = 0, onDone = {}) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.annotate_play)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.annotate_play)).assertIsDisplayed()
    }

    @Test
    fun recordButtonRequestsMicWhenUngranted() {
        runBlocking { app.seedItem(5) }
        compose.setScreen(app) { AnnotateScreen(mediaIds = listOf(5), startIndex = 0, onDone = {}) }
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithText(strings.getString(R.string.annotate_hold_to_record))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // no mic permission: the click path launches the permission request rather than recording
        compose.onNodeWithText(strings.getString(R.string.annotate_hold_to_record)).performClick()
        compose.onNodeWithText(strings.getString(R.string.annotate_hold_to_record)).assertIsDisplayed()
    }

    @Test
    fun failedWriteShowsErrorMessage() {
        runBlocking { app.seedMemory(8, note = "existing") }
        (app.access as cam.engram.app.FakeContentAccess).corruptWrites = true
        compose.setScreen(app) { AnnotateScreen(mediaIds = listOf(8), startIndex = 0, onDone = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("will not verify")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.annotate_save)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.annotate_save)).performClick()
        // the corrupt write fails verification; the SaveUi.Error branch renders the reason
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("no records", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("no records", substring = true).assertIsDisplayed()
    }

    @Test
    fun rejectedWriteDrivesConsentBranch() {
        runBlocking { app.seedMemory(9, note = "existing") }
        (app.access as cam.engram.app.FakeContentAccess).rejectWrites = true
        compose.setScreen(app) { AnnotateScreen(mediaIds = listOf(9), startIndex = 0, onDone = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("needs consent")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.annotate_save)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.annotate_save)).performClick()
        // rejected write -> SaveUi.Rejected -> consentGate branch; screen must not crash
        compose.waitForIdle()
        compose.onRoot().assertExists()
    }

    @Test
    fun recordButtonRunsGestureWhenMicGranted() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        runBlocking { app.seedItem(6) }
        compose.setScreen(app) { AnnotateScreen(mediaIds = listOf(6), startIndex = 0, onDone = {}) }
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithText(strings.getString(R.string.annotate_hold_to_record))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // press-and-release drives the detectTapGestures onPress/onStop path (noop recorder)
        compose.onNodeWithText(strings.getString(R.string.annotate_hold_to_record)).performTouchInput {
            down(center)
            up()
        }
        compose.onRoot().assertExists()
    }

    @Test
    fun noteFieldAndRecordButtonDisabledWhileSaving() {
        // park the write on a test scheduler so the Saving state is observable
        val scheduler = TestCoroutineScheduler()
        val slowApp = fakeContainer(io = StandardTestDispatcher(scheduler))
        runBlocking { slowApp.seedMemory(13, note = "existing") }
        var done = false
        compose.setScreen(slowApp) { AnnotateScreen(mediaIds = listOf(13), startIndex = 0, onDone = { done = true }) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("in flight")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.annotate_save)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.annotate_save)).performClick()

        // while the write is parked every input surface is frozen (a disabled text
        // field loses its SetText action, so match it by its visible text)
        compose.onNodeWithText("in flight").assertIsNotEnabled()
        compose.onNodeWithText(strings.getString(R.string.annotate_hold_to_record)).assertIsNotEnabled()

        // releasing the scheduler completes the save and navigation resumes
        compose.waitUntil(10_000) {
            scheduler.advanceUntilIdle()
            done
        }
        assertTrue(done)
        slowApp.db.close()
    }

    @Test
    fun savingImageWithoutLocationWarnsFirstThenSaves() {
        // location denied: the first image save shows the strip warning; confirming proceeds
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        runBlocking { app.seedMemory(12, note = "existing") }
        var done = false
        compose.setScreen(app) { AnnotateScreen(mediaIds = listOf(12), startIndex = 0, onDone = { done = true }) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("keep or not")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.getString(R.string.annotate_save)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.getString(R.string.annotate_save)).performClick()
        // the warning dialog appears instead of saving immediately
        val confirm = strings.getString(R.string.annotate_location_warning_confirm)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(confirm).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(!done, "save must wait for confirmation")
        // confirming proceeds with the write, advancing the single-item queue to onDone
        compose.onNodeWithText(confirm).performClick()
        compose.waitUntil(5_000) { done }
        assertTrue(done)
    }
}
