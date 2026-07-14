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
import cam.engram.app.seedItem
import cam.engram.app.seedMemory
import cam.engram.app.setScreen
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.EnrichmentPayload
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import cam.engram.format.xmp.XmpCoreEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class MemoryDetailScreenTest : ScreenTest() {
    private val app = fakeContainer().closingDb()
    private val strings = ApplicationProvider.getApplicationContext<Context>()

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

    @Test
    fun showsNoteHistoryAndEnrichment() {
        runBlocking {
            val records =
                listOf(
                    EngramRecord(RecordKind.Note, 1, "first look".encodeToByteArray()),
                    EngramRecord(RecordKind.Note, 2, "second thought".encodeToByteArray()),
                    EngramRecord(
                        RecordKind.Enrichment,
                        3,
                        EnrichmentPayload(mapOf("place" to "Lakeside", "weather" to "Clear")).encode(),
                    ),
                )
            val bytes = JpegEmbedder(XmpCoreEngine()).embed(SyntheticMedia.jpegPlain(), records, "second thought")
            (app.access as FakeContentAccess).files["content://media/5"] = bytes
            app.seedItem(5, recordCount = records.size)
        }
        compose.setScreen(app) { MemoryDetailScreen(mediaId = 5, onAnnotate = {}, onBack = {}) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("second thought").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("second thought").assertIsDisplayed() // current note
        compose.onNodeWithText("Lakeside · Clear").assertIsDisplayed() // enrichment line
        compose.onNodeWithText("• first look").assertIsDisplayed() // older note under history
    }
}
