package cam.engram.app

import androidx.exifinterface.media.ExifInterface
import cam.engram.app.data.media.SourceItem
import cam.engram.app.enrich.PlaceProvider
import cam.engram.app.enrich.WeatherProvider
import cam.engram.app.enrich.WeatherReading
import cam.engram.app.writeback.Annotation
import cam.engram.app.writeback.WriteOutcome
import cam.engram.format.records.RecordKind
import cam.engram.format.records.RecordStream
import cam.engram.format.testing.SyntheticMedia
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Enrichment end to end through the container (D22): reconcile prefetches via
 * the INJECTED providers into the cache, and the next save embeds the cached
 * record. Before the providers became constructor params this loop was only
 * reachable by hand-building Enricher and Reconciler around the container.
 */
@RunWith(RobolectricTestRunner::class)
class EnrichmentContainerTest {
    private var container: AppContainer? = null

    // the preferences DataStore is a process singleton behind the app context, so a
    // flag set here would leak into other tests on the same Gradle worker (it did on
    // CI: SettingsStoreTest asserts the default is off); always reset it
    @After
    fun tearDown() =
        runBlocking {
            container?.settings?.setEnrichmentNetwork(false)
            container?.db?.close()
            Unit
        }

    // a jpeg with real EXIF GPS: enrichment exits early without coordinates, so a
    // plain fixture would pass while exercising nothing
    private fun gpsJpeg(): ByteArray {
        val f = File.createTempFile("gps", ".jpg").apply { writeBytes(SyntheticMedia.jpegPlain()) }
        ExifInterface(f.path).apply {
            setLatLong(41.65, 41.63)
            saveAttributes()
        }
        return f.readBytes().also { f.delete() }
    }

    @Test
    fun reconcilePrefetchesThroughInjectedProvidersAndTheNextSaveEmbedsIt() =
        runBlocking {
            var placeCalls = 0
            var weatherCalls = 0
            val access = FakeContentAccess()
            val container =
                fakeContainer(
                    access = access,
                    placeProvider =
                        object : PlaceProvider {
                            override suspend fun place(
                                lat: Double,
                                lon: Double,
                            ): String? {
                                placeCalls++
                                return "Batumi"
                            }
                        },
                    weatherProvider =
                        object : WeatherProvider {
                            override suspend fun weather(
                                lat: Double,
                                lon: Double,
                                atMillis: Long,
                            ): WeatherReading? {
                                weatherCalls++
                                return WeatherReading("clear", 24.0, "fake")
                            }
                        },
                )
            this@EnrichmentContainerTest.container = container
            container.settings.setEnrichmentNetwork(true)
            val uri = "content://media/images/1"
            access.files[uri] = gpsJpeg()
            (container.source as FakeMediaSource).items +=
                SourceItem(
                    mediaId = 1,
                    uri = uri,
                    isVideo = false,
                    mime = "image/jpeg",
                    relativePath = "DCIM/Camera/",
                    takenAtMillis = 111,
                    sizeBytes = access.files[uri]!!.size.toLong(),
                    dateModified = 1,
                )

            container.reconciler.reconcile()
            assertEquals(1, placeCalls, "prefetch must reach the injected place provider")
            assertEquals(1, weatherCalls, "prefetch must reach the injected weather provider")
            assertNotNull(container.db.enrichmentCache().byId(1), "prefetch fills the enrichment cache")

            val outcome = container.writeBack.write(container.db.media().byId(1)!!, Annotation("пик", null))
            assertIs<WriteOutcome.Success>(outcome)
            val kinds = RecordStream.scan(access.files[uri]!!).mapNotNull { it.decoded.record?.kind }
            assertTrue(RecordKind.Enrichment in kinds, "the cached enrichment rides the next save: $kinds")
        }

    @Test
    fun disabledEnrichmentNeverTouchesTheProviders() =
        runBlocking {
            var calls = 0
            val access = FakeContentAccess()
            val container =
                fakeContainer(
                    access = access,
                    placeProvider =
                        object : PlaceProvider {
                            override suspend fun place(
                                lat: Double,
                                lon: Double,
                            ): String? {
                                calls++
                                return null
                            }
                        },
                )
            this@EnrichmentContainerTest.container = container
            val uri = "content://media/images/2"
            access.files[uri] = gpsJpeg()
            (container.source as FakeMediaSource).items +=
                SourceItem(2, uri, false, "image/jpeg", "DCIM/Camera/", 222, access.files[uri]!!.size.toLong(), 2)

            container.reconciler.reconcile()
            assertEquals(0, calls, "the network toggle is off by default: no provider call")
        }
}
