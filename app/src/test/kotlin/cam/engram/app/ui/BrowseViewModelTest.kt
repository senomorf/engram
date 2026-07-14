package cam.engram.app.ui

import androidx.test.core.app.ApplicationProvider
import cam.engram.app.AppContainer
import cam.engram.app.awaitValue
import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.db.MemoryFts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BrowseViewModelTest {
    private val db = EngramDb.inMemory(ApplicationProvider.getApplicationContext())

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun container() = AppContainer(ApplicationProvider.getApplicationContext(), db = db)

    private suspend fun seed(
        id: Long,
        note: String,
    ) {
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
                    recordCount = 1,
                    payloadLength = 0,
                    lastScanMillis = 0,
                ),
            ),
        )
        db.search().upsert(MemoryFts(id, note))
    }

    @Test
    fun searchReturnsFtsMatches() =
        runBlocking {
            seed(1, "sunrise over the lake")
            seed(2, "birthday cake candles")
            val vm = BrowseViewModel(container())
            vm.onQueryChange("sunrise")
            vm.results.awaitValue { it != null }
            assertEquals(listOf(1L), vm.results.value!!.map { it.mediaId })
        }

    @Test
    fun blankQueryClearsResults() =
        runBlocking {
            seed(1, "sunrise")
            val vm = BrowseViewModel(container())
            vm.onQueryChange("sunrise")
            vm.results.awaitValue { it != null }
            assertNotNull(vm.results.value)
            vm.onQueryChange("   ")
            vm.results.awaitValue { it == null }
            assertNull(vm.results.value)
        }

    @Test
    fun ftsSpecialCharactersAreSanitized() =
        runBlocking {
            seed(1, "sunrise")
            val vm = BrowseViewModel(container())
            // punctuation raw FTS MATCH would choke on; sanitize must strip it
            vm.onQueryChange("sun\"rise():*^")
            vm.results.awaitValue { it != null }
            assertEquals(listOf(1L), vm.results.value!!.map { it.mediaId })
        }
}
