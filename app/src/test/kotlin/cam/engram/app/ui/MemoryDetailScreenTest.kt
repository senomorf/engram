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
import cam.engram.app.seedItem
import cam.engram.app.seedMemory
import cam.engram.app.setScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class MemoryDetailScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = fakeContainer()
    private val strings = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() = app.db.close()

    @Test
    fun showsSeededNoteAndVoiceSection() {
        runBlocking { app.seedMemory(1, note = "at the lake") }
        compose.setScreen(app) { MemoryDetailScreen(mediaId = 1, onAnnotate = {}, onBack = {}) }
        // note + audio load via a Room flow + LaunchedEffect, so wait for them to land
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("at the lake").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("at the lake").assertIsDisplayed()
        compose.onNodeWithText(strings.getString(R.string.detail_voice)).assertIsDisplayed()
    }

    @Test
    fun addMoreButtonInvokesOnAnnotate() {
        runBlocking { app.seedItem(2) }
        var annotated: Long? = null
        compose.setScreen(app) { MemoryDetailScreen(mediaId = 2, onAnnotate = { annotated = it }, onBack = {}) }
        compose.onNodeWithText(strings.getString(R.string.detail_add_more)).performClick()
        assertEquals(2L, annotated)
    }
}
