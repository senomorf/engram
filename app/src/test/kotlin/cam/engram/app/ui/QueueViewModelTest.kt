package cam.engram.app.ui

import androidx.test.core.app.ApplicationProvider
import cam.engram.app.AppContainer
import cam.engram.app.data.db.EngramDb
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
}
