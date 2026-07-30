package cam.engram.app.ui

import androidx.test.core.app.ApplicationProvider
import cam.engram.app.AppContainer
import cam.engram.app.data.db.EngramDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
            // each setter returns its Job, so the test awaits the writes instead of polling
            // the flow under a wall-clock timeout, which went red on a loaded machine (#127).
            // setDigest / setDigestHour also reschedule WorkManager; exercise both paths.
            listOf(
                vm.setScreenshots(false),
                vm.setBurst(true),
                vm.setEnrichmentNetwork(false),
                vm.setDynamicColor(false),
                vm.setDigest(false),
                vm.setDigestHour(9),
            ).joinAll()
            val s = container.settings.current()
            assertEquals(false, s.includeScreenshots)
            assertEquals(true, s.burstNudgeEnabled)
            assertEquals(false, s.enrichmentNetworkEnabled)
            assertEquals(false, s.dynamicColor)
            assertEquals(false, s.digestEnabled)
            assertEquals(9, s.digestHour)
        }
}
