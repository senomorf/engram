package photos.engram.app

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import photos.engram.app.audio.VoiceRecorder
import photos.engram.app.audio.VoiceRecorderFactory
import photos.engram.app.data.db.EngramDb
import photos.engram.app.data.db.MediaItemEntity
import photos.engram.app.ui.AnnotateViewModel
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AnnotateViewModelTest {
    private val db = EngramDb.inMemory(ApplicationProvider.getApplicationContext())
    private val draftsDir =
        File.createTempFile("drafts", "").let { f ->
            f.delete()
            f.mkdirs().let { _ -> f }
        }

    private val fakeRecorder =
        object : VoiceRecorder {
            var target: File? = null

            override fun start(output: File) {
                target = output
            }

            override fun stop(): Boolean {
                target?.writeBytes(ByteArray(64) { 1 })
                return true
            }
        }

    private fun container(): AppContainer =
        AppContainer(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
            recorderFactory =
                object : VoiceRecorderFactory {
                    override fun create(): VoiceRecorder = fakeRecorder
                },
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runBlocking {
            db.media().upsert(
                listOf(
                    MediaItemEntity(
                        mediaId = 7,
                        uri = "content://x/7",
                        isVideo = false,
                        mime = "image/jpeg",
                        relativePath = "DCIM/Camera/",
                        takenAtMillis = 1,
                        sizeBytes = 10,
                        recordCount = 0,
                        payloadLength = 0,
                        lastScanMillis = 0,
                    ),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun draftPersistsTextAndAudio() =
        runTest {
            val vm = AnnotateViewModel(container(), mediaId = 7, draftsDir = draftsDir)
            advanceUntilIdle()
            vm.onTextChange("лунная ночь")
            advanceUntilIdle()
            assertEquals("лунная ночь", db.drafts().byId(7)!!.text)

            vm.startRecording()
            vm.stopRecording()
            advanceUntilIdle()
            val draft = db.drafts().byId(7)!!
            assertNotNull(draft.audioPath)
            assertEquals(true, File(draft.audioPath!!).exists())

            vm.discardAudio()
            advanceUntilIdle()
            assertNull(db.drafts().byId(7)!!.audioPath)
        }

    @Test
    fun blankDraftIsRemoved() =
        runTest {
            val vm = AnnotateViewModel(container(), mediaId = 7, draftsDir = draftsDir)
            advanceUntilIdle()
            vm.onTextChange("x")
            advanceUntilIdle()
            vm.onTextChange("")
            advanceUntilIdle()
            assertNull(db.drafts().byId(7))
        }
}
