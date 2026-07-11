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
