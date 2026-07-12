package cam.engram.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cam.engram.app.AppContainer
import cam.engram.app.FakeContentAccess
import cam.engram.app.data.db.EngramDb
import cam.engram.app.fakeContainer
import cam.engram.format.testing.SyntheticMedia
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QueueViewModelTest {
    private val db = EngramDb.inMemory(ApplicationProvider.getApplicationContext())

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private suspend fun settle() = delay(500)

    private fun vm() = QueueViewModel(AppContainer(ApplicationProvider.getApplicationContext(), db = db))

    @Test
    fun refreshReconcilesAndClearsBusy() =
        runBlocking {
            val vm = vm()
            vm.refresh()
            settle()
            // reconcile over an empty MediaStore leaves nothing stripped and the flag cleared
            assertEquals(false, vm.busy.value)
            assertEquals(0, vm.stripped.value.size)
        }

    @Test
    fun repairAllWithNothingStrippedIsNoOp() =
        runBlocking {
            val vm = vm()
            vm.repairAll()
            settle()
            assertEquals(0, vm.stripped.value.size)
        }

    @Test
    fun consentHandledClearsConsentAndRetries() =
        runBlocking {
            val vm = vm()
            vm.consentHandled()
            settle()
            assertNull(vm.repairNeedsConsent.value)
        }

    // finding C2: a write stranded mid-restore by a lost grant surfaces in the queue until the
    // user grants consent; retrying then restores the original and clears the affordance
    @Test
    fun refreshSurfacesPendingRecoveryUntilConsentThenRestores() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val access = FakeContentAccess()
            val app = fakeContainer(context = context, db = db, access = access)
            val uri = "content://media/40"
            val original = SyntheticMedia.jpegPlain()
            access.files[uri] = ByteArray(3) { 0x11 } // crash left the target truncated
            val backupDir =
                File(context.filesDir, "writeback").apply {
                    deleteRecursively()
                    mkdirs()
                }
            File(backupDir, "40.bak").writeBytes(original) // the pristine original
            File(backupDir, "40.meta").writeText("$uri\nfalse\nimage/jpeg\ndeadbeef")
            access.rejectRestore = true // the restore cannot open the target without a grant

            val vm = QueueViewModel(app)
            vm.refresh()
            settle()
            assertEquals(listOf(uri), vm.pendingRecovery.value)

            access.rejectRestore = false // the user granted the write consent
            vm.recoveryConsentHandled()
            settle()
            assertEquals(emptyList(), vm.pendingRecovery.value)
            assertContentEquals(original, access.files[uri])
        }
}
