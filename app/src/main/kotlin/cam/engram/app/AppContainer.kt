package cam.engram.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import cam.engram.app.audio.MediaVoiceRecorder
import cam.engram.app.audio.VoiceRecorderFactory
import cam.engram.app.data.SettingsStore
import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.media.ContentAccess
import cam.engram.app.data.media.MediaSource
import cam.engram.app.data.media.MediaStoreSource
import cam.engram.app.data.media.ResolverContentAccess
import cam.engram.app.data.scan.RecordScanner
import cam.engram.app.domain.MemoryReader
import cam.engram.app.domain.Reconciler
import cam.engram.app.enrich.Enricher
import cam.engram.app.enrich.EnrichmentGateway
import cam.engram.app.enrich.ExifGpsReader
import cam.engram.app.enrich.GeocoderPlaceProvider
import cam.engram.app.enrich.OpenMeteoWeatherProvider
import cam.engram.app.enrich.PlaceProvider
import cam.engram.app.enrich.WeatherProvider
import cam.engram.app.export.ArchiveExporter
import cam.engram.app.writeback.ConsentGate
import cam.engram.app.writeback.MediaStoreConsentGate
import cam.engram.app.writeback.MediaWriteBack
import cam.engram.app.writeback.StripRepair
import cam.engram.format.records.EngramRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Manual dependency container: one place wires the app. A DI framework buys
 * nothing at this size and costs a compiler plugin (design principle: simplest
 * working solution).
 */
class AppContainer(
    context: Context,
    val db: EngramDb = EngramDb.build(context),
    val access: ContentAccess =
        ResolverContentAccess(
            context.contentResolver,
            requireOriginal = {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            },
        ),
    val source: MediaSource = MediaStoreSource(context.contentResolver),
    val settings: SettingsStore = SettingsStore(context.applicationContext),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    val recorderFactory: VoiceRecorderFactory =
        object : VoiceRecorderFactory {
            override fun create() = MediaVoiceRecorder(context.applicationContext)
        },
    // the one seam fakeContainer() could not reach: real providers stay the eager
    // defaults, tests substitute counting fakes so enrichment is exercisable
    // through the container instead of by hand-building Enricher
    placeProvider: PlaceProvider = GeocoderPlaceProvider(context.applicationContext),
    weatherProvider: WeatherProvider = OpenMeteoWeatherProvider(),
) {
    val appContext: Context = context.applicationContext
    val scanner: RecordScanner = RecordScanner(access)
    val memoryReader: MemoryReader = MemoryReader(access, io)

    private val enrichmentGateway =
        EnrichmentGateway(
            settings = settings,
            gpsReader = ExifGpsReader(access),
            enricher = Enricher(placeProvider, weatherProvider),
        )

    val reconciler: Reconciler =
        Reconciler(
            db = db,
            source = source,
            scanner = scanner,
            includeScreenshots = { settings.current().includeScreenshots },
            io = io,
            enrichmentPrefetch = { enrichmentGateway.fetch(it)?.encode() },
            clock = System::currentTimeMillis,
        )

    val consentGate: ConsentGate = MediaStoreConsentGate(context.contentResolver)

    val writeBack: MediaWriteBack =
        MediaWriteBack(
            db = db,
            access = access,
            scanner = scanner,
            backupDir = File(context.filesDir, "writeback"),
            io = io,
            cachedEnrichment = { item ->
                db.enrichmentCache().byId(item.mediaId)?.let { EngramRecord.decodeAt(it.recordBlob, 0)?.record }
            },
        )

    val stripRepair: StripRepair = StripRepair(db, writeBack, scanner)

    // container-built so the private io dispatcher stays private: the exporter does
    // its own withContext(io), keeping full-media hashing off the main thread (R8)
    val archiveExporter: ArchiveExporter = ArchiveExporter(db, access, io)
}
