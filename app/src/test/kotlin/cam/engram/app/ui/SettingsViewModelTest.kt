package cam.engram.app.ui

import androidx.test.core.app.ApplicationProvider
import cam.engram.app.AppContainer
import cam.engram.app.data.db.EngramDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val db = EngramDb.inMemory(ApplicationProvider.getApplicationContext())

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun everySetterPersistsThroughStore() =
        runBlocking {
            val container = AppContainer(ApplicationProvider.getApplicationContext(), db = db)
            val vm = SettingsViewModel(container)
            vm.setScreenshots(false)
            vm.setBurst(true)
            vm.setEnrichmentNetwork(false)
            vm.setDynamicColor(false)
            // setDigest / setDigestHour also reschedule WorkManager; exercise both paths
            vm.setDigest(false)
            vm.setDigestHour(9)
            // the setters are fire-and-forget on viewModelScope; wait for the end state to land
            // rather than race a fixed delay (flaky under load)
            val s =
                withTimeout(5_000) {
                    container.settings.settings.first {
                        !it.includeScreenshots &&
                            it.burstNudgeEnabled &&
                            !it.enrichmentNetworkEnabled &&
                            !it.dynamicColor &&
                            !it.digestEnabled &&
                            it.digestHour == 9
                    }
                }
            assertEquals(false, s.includeScreenshots)
            assertEquals(true, s.burstNudgeEnabled)
            assertEquals(false, s.enrichmentNetworkEnabled)
            assertEquals(false, s.dynamicColor)
            assertEquals(false, s.digestEnabled)
            assertEquals(9, s.digestHour)
        }
}
