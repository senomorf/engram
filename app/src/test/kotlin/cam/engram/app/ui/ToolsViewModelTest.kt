package cam.engram.app.ui

import androidx.test.core.app.ApplicationProvider
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.db.RecordCacheEntity
import cam.engram.app.fakeContainer
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.read.Survival
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import cam.engram.format.xmp.XmpCoreEngine
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
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Drives export and verify through the tools view model, off the caller's thread. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ToolsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun exportParksOnIoAndLandsDone() =
        runBlocking {
            val scheduler = TestCoroutineScheduler()
            val app =
                fakeContainer(
                    context = ApplicationProvider.getApplicationContext(),
                    io = StandardTestDispatcher(scheduler),
                )
            val bytes = SyntheticMedia.jpegPlain()
            (app.access as cam.engram.app.FakeContentAccess).files["content://media/1"] = bytes
            app.db.media().upsert(
                listOf(MediaItemEntity(1, "content://media/1", false, "image/jpeg", "DCIM/", 1, 10, 1, 1, 0, 0)),
            )
            app.db.recordCache().upsert(
                RecordCacheEntity(
                    1,
                    1,
                    10,
                    EngramRecord(RecordKind.Note, 1, "kept".encodeToByteArray()).encode(),
                    1,
                    0,
                ),
            )
            val vm = ToolsViewModel(app)
            val written = mutableMapOf<String, ByteArray>()
            vm.export { name, data ->
                written[name] = data
                true
            }
            // the whole export is parked on the io scheduler: the caller thread saw
            // only the state flip, none of the hashing or writing
            assertIs<ExportState.Running>(vm.exportState.value)
            assertEquals(0, written.size)
            repeat(50) {
                scheduler.advanceUntilIdle()
                if (vm.exportState.value !is ExportState.Running) return@repeat
                delay(100)
            }
            val done = assertIs<ExportState.Done>(vm.exportState.value)
            assertEquals(1, done.result.itemCount)
            assertEquals(0, done.result.failedCount)
            app.db.close()
        }

    // issue #92: shelved backups (a reused media id kept the displaced photo's only copy)
    // are listed, can be streamed out to a user-chosen location, and can be discarded
    @Test
    fun shelvedBackupsAreListedSavedAndDiscarded() =
        runBlocking {
            val app = fakeContainer(context = ApplicationProvider.getApplicationContext())
            val backupDir =
                java.io.File(
                    ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
                    "writeback",
                )
            backupDir.deleteRecursively()
            backupDir.mkdirs()
            // a real JPEG head so the copy is recognized and named as a photo
            val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(16) { it.toByte() }
            java.io.File(backupDir, "40.100.0.bak.orphan").writeBytes(payload)
            val vm = ToolsViewModel(app)

            vm.refreshShelved()
            awaitShelved(vm) { it.backups.size == 1 }
            assertEquals(
                payload.size.toLong(),
                vm.shelvedState.value.backups
                    .single()
                    .sizeBytes,
            )

            // the sink hands back a stream per name; capture what the copy writes into it
            vm.saveShelved { name, mime ->
                savedMimes[name] = mime
                java.io.ByteArrayOutputStream().also { savedStreams[name] = it }
            }
            awaitShelved(vm) { it.savedCount == 1 }
            // named and typed by what it is, so the user can open the recovered photo (#92)
            val saved = "engram-recovered-40.100.0.jpg"
            assertEquals(payload.toList(), savedStreams.getValue(saved).toByteArray().toList())
            assertEquals("image/jpeg", savedMimes[saved])

            vm.discardShelved()
            awaitShelved(vm) { it.backups.isEmpty() }
            assertEquals(false, java.io.File(backupDir, "40.100.0.bak.orphan").exists())
            app.db.close()
        }

    private val savedStreams = mutableMapOf<String, java.io.ByteArrayOutputStream>()
    private val savedMimes = mutableMapOf<String, String>()

    private suspend fun awaitShelved(
        vm: ToolsViewModel,
        predicate: (ShelvedState) -> Boolean,
    ) {
        repeat(50) {
            if (predicate(vm.shelvedState.value)) return
            delay(50)
        }
        error("shelved state never satisfied the predicate: ${vm.shelvedState.value}")
    }

    @Test
    fun nullSinkFailsWithoutRunning() {
        val app = fakeContainer(context = ApplicationProvider.getApplicationContext())
        val vm = ToolsViewModel(app)
        vm.export(null)
        assertIs<ExportState.Failed>(vm.exportState.value)
        app.db.close()
    }

    @Test
    fun verifyReportsSurvival() =
        runBlocking {
            val app = fakeContainer(context = ApplicationProvider.getApplicationContext())
            val note = EngramRecord(RecordKind.Note, 1, "still here".encodeToByteArray())
            (app.access as cam.engram.app.FakeContentAccess).files["content://in/1"] =
                JpegEmbedder(XmpCoreEngine()).embed(SyntheticMedia.jpegPlain(), listOf(note), "still here")
            val vm = ToolsViewModel(app)
            vm.verify("content://in/1")
            // BackupVerifier hops to the real io dispatcher internally: wait for the result
            repeat(50) {
                if (vm.verifyState.value is VerifyState.Done) return@repeat
                delay(100)
            }
            val done = assertIs<VerifyState.Done>(vm.verifyState.value)
            assertEquals(Survival.FULL, done.survival)
            app.db.close()
        }
}
