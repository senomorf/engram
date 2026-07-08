package cam.engram.app

import androidx.test.core.app.ApplicationProvider
import cam.engram.app.audio.VoiceRecorder
import cam.engram.app.audio.VoiceRecorderFactory
import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.ui.AnnotateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Room's suspend DAOs run on Room's own executors, which a TestScheduler cannot
 * advance, so these drive real coroutines with runBlocking and settle past the
 * 400ms draft debounce explicitly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AnnotateViewModelTest {
    private val db = EngramDb.inMemory(ApplicationProvider.getApplicationContext())
    private val draftsDir =
        File.createTempFile("engramdrafts", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }

    private val fakeRecorder =
        object : VoiceRecorder {
            private var target: File? = null

            override fun start(output: File) {
                target = output
            }

            override fun stop(): Boolean {
                target?.apply {
                    parentFile?.mkdirs()
                    writeBytes(ByteArray(64) { 1 })
                }
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

    private suspend fun seedItem() {
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
                    dateModified = 1,
                    recordCount = 0,
                    payloadLength = 0,
                    lastScanMillis = 0,
                ),
            ),
        )
    }

    @Before
    fun setUp() {
        // viewModelScope dispatches on Main; Unconfined runs those launches
        // eagerly while runBlocking + settle() covers Room's real async work
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    // past the 400ms debounce plus room write settling
    private suspend fun settle() = delay(700)

    @Test
    fun draftPersistsTextAndAudio() =
        runBlocking {
            seedItem()
            val vm = AnnotateViewModel(container(), mediaId = 7, draftsDir = draftsDir)
            settle()
            vm.onTextChange("лунная ночь")
            settle()
            assertEquals("лунная ночь", db.drafts().byId(7)!!.text)

            vm.startRecording()
            vm.stopRecording()
            settle()
            val draft = db.drafts().byId(7)!!
            assertNotNull(draft.audioPath)
            assertEquals(true, File(draft.audioPath!!).exists())

            vm.discardAudio()
            settle()
            assertNull(db.drafts().byId(7)!!.audioPath)
        }

    @Test
    fun blankDraftIsRemoved() =
        runBlocking {
            seedItem()
            val vm = AnnotateViewModel(container(), mediaId = 7, draftsDir = draftsDir)
            settle()
            vm.onTextChange("x")
            settle()
            assertNotNull(db.drafts().byId(7))
            vm.onTextChange("")
            settle()
            assertNull(db.drafts().byId(7))
        }
}
