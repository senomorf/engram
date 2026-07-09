package cam.engram.app

import androidx.test.core.app.ApplicationProvider
import cam.engram.app.data.EngramSettings
import cam.engram.app.data.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    private val store = SettingsStore(ApplicationProvider.getApplicationContext())

    @Test
    fun dictationLanguageSetsAndClears() =
        runBlocking {
            store.setDictationLanguage("ru-RU")
            assertEquals("ru-RU", store.current().dictationLanguage)
            store.setDictationLanguage(null)
            assertNull(store.current().dictationLanguage)
        }

    @Test
    fun onboardingFlagPersists() =
        runBlocking {
            store.setOnboardingDone(true)
            assertEquals(true, store.current().onboardingDone)
        }

    @Test
    fun enrichmentNetworkIsOffByDefaultAndOptsIn() =
        runBlocking {
            // privacy default: photo GPS never leaves the device until the user opts in (finding C)
            assertEquals(false, EngramSettings().enrichmentNetworkEnabled)
            assertEquals(false, store.current().enrichmentNetworkEnabled)
            store.setEnrichmentNetwork(true)
            assertEquals(true, store.current().enrichmentNetworkEnabled)
        }
}
