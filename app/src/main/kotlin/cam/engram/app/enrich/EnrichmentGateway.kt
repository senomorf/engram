package cam.engram.app.enrich

import cam.engram.app.data.SettingsStore
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * Fetches an enrichment record for one item from EXIF-derived GPS and the
 * providers. This is the NETWORK path: it runs in the background prefetcher
 * (reconcile), never in the user save path (review F5). The save path reads
 * the pre-fetched record from enrichment_cache instead.
 */
class EnrichmentGateway(
    private val settings: SettingsStore,
    private val gpsReader: ExifGpsReader,
    private val enricher: Enricher,
    private val writerId: String = "engram-android",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()

    suspend fun fetch(item: MediaItemEntity): EngramRecord? {
        if (!settings.current().enrichmentNetworkEnabled) return null
        val context = gpsReader.read(item.uri, item.isVideo, item.takenAtMillis) ?: return null
        // withTimeoutOrNull returns null on its own timeout and propagates outer
        // cancellation; the providers below are the ones that must not swallow it
        val payload = withTimeoutOrNull(TIMEOUT_MILLIS) { enricher.enrich(context) } ?: return null
        val id = ByteArray(EngramRecord.ID_LENGTH).also { random.nextBytes(it) }
        return EngramRecord(RecordKind.Enrichment, clock(), payload.encode(), id, writerId)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 9000L
    }
}
