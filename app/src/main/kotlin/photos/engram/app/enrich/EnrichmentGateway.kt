package photos.engram.app.enrich

import kotlinx.coroutines.withTimeoutOrNull
import photos.engram.app.data.SettingsStore
import photos.engram.app.data.db.MediaItemEntity
import photos.engram.format.records.EngramRecord
import photos.engram.format.records.RecordKind
import java.security.SecureRandom

/**
 * Bridges settings, EXIF-derived context and providers into one Enrichment
 * record for the write-back pipeline. Returns null unless enrichment is on,
 * the file carries GPS, and providers answer within the timeout.
 */
class EnrichmentGateway(
    private val settings: SettingsStore,
    private val gpsReader: ExifGpsReader,
    private val enricher: Enricher,
    private val writerId: String = "engram-android",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()

    suspend fun recordFor(item: MediaItemEntity): EngramRecord? {
        if (!settings.current().enrichmentNetworkEnabled) return null
        val context = gpsReader.read(item.uri, item.isVideo, item.takenAtMillis) ?: return null
        val payload = withTimeoutOrNull(TIMEOUT_MILLIS) { enricher.enrich(context) } ?: return null
        val id = ByteArray(EngramRecord.ID_LENGTH).also { random.nextBytes(it) }
        return EngramRecord(RecordKind.Enrichment, clock(), payload.encode(), id, writerId)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 9000L
    }
}
