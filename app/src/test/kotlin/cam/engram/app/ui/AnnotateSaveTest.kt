package cam.engram.app.ui

import androidx.test.core.app.ApplicationProvider
import cam.engram.app.AppContainer
import cam.engram.app.FakeContentAccess
import cam.engram.app.audio.VoiceRecorder
import cam.engram.app.audio.VoiceRecorderFactory
import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.format.records.RecordStream
import cam.engram.format.testing.SyntheticMedia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Drives the full annotate -> save -> write-back path through the ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AnnotateSaveTest {
    private val db = EngramDb.inMemory(ApplicationProvider.getApplicationContext())
    private val access = FakeContentAccess()
    private val draftsDir =
        File.createTempFile("drafts", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    private val noopRecorder =
        object : VoiceRecorder {
            override fun start(output: File) = Unit

            override fun stop(): Boolean = false
        }

    private val workingRecorder =
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

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private suspend fun settle() = delay(700)

    private fun container(
        recorder: VoiceRecorder = noopRecorder,
        io: CoroutineDispatcher = Dispatchers.IO,
    ) = AppContainer(
        context = ApplicationProvider.getApplicationContext(),
        db = db,
        access = access,
        io = io,
        recorderFactory =
            object : VoiceRecorderFactory {
                override fun create() = recorder
            },
    )

    // the io write is parked on the test scheduler while Room work rides real threads:
    // alternate advancing the scheduler with real settling until the save resolves
    private suspend fun drainSave(
        scheduler: TestCoroutineScheduler,
        vm: AnnotateViewModel,
    ) {
        repeat(50) {
            scheduler.advanceUntilIdle()
            if (vm.ui.value.save !is SaveUi.Saving) return
            delay(100)
        }
    }

    private suspend fun seed(id: Long) {
        access.files["content://media/$id"] = SyntheticMedia.jpegPlain()
        db.media().upsert(
            listOf(
                MediaItemEntity(
                    mediaId = id,
                    uri = "content://media/$id",
                    isVideo = false,
                    mime = "image/jpeg",
                    relativePath = "DCIM/Camera/",
                    takenAtMillis = id,
                    sizeBytes = 10,
                    dateModified = id,
                    recordCount = 0,
                    payloadLength = 0,
                    lastScanMillis = 0,
                ),
            ),
        )
    }

    @Test
    fun saveWritesNoteAndReportsSaved() =
        runBlocking {
            seed(7)
            val vm = AnnotateViewModel(container(), mediaId = 7, draftsDir = draftsDir)
            settle()
            vm.onTextChange("first light")
            settle()
            vm.save()
            settle()
            assertIs<SaveUi.Saved>(vm.ui.value.save)
            assertEquals(1, RecordStream.scan(access.files["content://media/7"]!!).count { it.decoded.crcOk })
            assertEquals(1, db.media().byId(7)!!.recordCount)
            // draft is cleared once the write verifies
            assertEquals(null, db.drafts().byId(7))
        }

    @Test
    fun saveWithNoContentShortCircuitsToSaved() =
        runBlocking {
            seed(8)
            val vm = AnnotateViewModel(container(), mediaId = 8, draftsDir = draftsDir)
            settle()
            vm.save()
            settle()
            assertIs<SaveUi.Saved>(vm.ui.value.save)
            // nothing to embed, so the file stays pristine
            assertEquals(0, db.media().byId(8)!!.recordCount)
        }

    // finding D: the snapshot being written must stay the truth; edits and new
    // recordings during the in-flight write are ignored, so the unconditional draft
    // delete on success can never discard newer content
    @Test
    fun inputsFreezeWhileSaveIsInFlight() =
        runBlocking {
            val scheduler = TestCoroutineScheduler()
            seed(9)
            val vm =
                AnnotateViewModel(
                    container(io = StandardTestDispatcher(scheduler)),
                    mediaId = 9,
                    draftsDir = draftsDir,
                )
            settle()
            vm.onTextChange("the snapshot")
            settle()
            vm.save()
            assertIs<SaveUi.Saving>(vm.ui.value.save)

            // the write is parked on the io scheduler: mutations must not land
            vm.onTextChange("typed during save")
            vm.startRecording()
            assertEquals("the snapshot", vm.ui.value.text)
            assertEquals(false, vm.ui.value.recording)

            drainSave(scheduler, vm)
            assertIs<SaveUi.Saved>(vm.ui.value.save)
            assertEquals("the snapshot", vm.ui.value.text)
            assertEquals(1, RecordStream.scan(access.files["content://media/9"]!!).count { it.decoded.crcOk })
            assertEquals(null, db.drafts().byId(9))
        }

    @Test
    fun saveIsBlockedWhileRecording() =
        runBlocking {
            seed(10)
            val vm = AnnotateViewModel(container(), mediaId = 10, draftsDir = draftsDir)
            settle()
            vm.onTextChange("note")
            settle()
            vm.startRecording()
            vm.save()
            // a save mid-gesture would snapshot a recording still being written
            assertIs<SaveUi.Idle>(vm.ui.value.save)
            assertEquals(0, RecordStream.scan(access.files["content://media/10"]!!).count { it.decoded.crcOk })

            vm.stopRecording()
            settle()
            vm.save()
            settle()
            assertIs<SaveUi.Saved>(vm.ui.value.save)
            assertEquals(1, RecordStream.scan(access.files["content://media/10"]!!).count { it.decoded.crcOk })
        }

    @Test
    fun discardAudioIgnoredWhileSaving() =
        runBlocking {
            val scheduler = TestCoroutineScheduler()
            seed(11)
            val vm =
                AnnotateViewModel(
                    container(recorder = workingRecorder, io = StandardTestDispatcher(scheduler)),
                    mediaId = 11,
                    draftsDir = draftsDir,
                )
            settle()
            vm.startRecording()
            vm.stopRecording()
            settle()
            val audio = vm.ui.value.audioPath!!
            vm.save()
            assertIs<SaveUi.Saving>(vm.ui.value.save)

            vm.discardAudio()
            assertEquals(audio, vm.ui.value.audioPath)
            assertEquals(true, File(audio).exists(), "the in-flight write's audio source must survive")

            drainSave(scheduler, vm)
            assertIs<SaveUi.Saved>(vm.ui.value.save)
            assertEquals(null, db.drafts().byId(11))
        }
}
