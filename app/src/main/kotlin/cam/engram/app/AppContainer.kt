package cam.engram.app

import android.content.Context
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
import cam.engram.app.writeback.ConsentGate
import cam.engram.app.writeback.MediaStoreConsentGate
import cam.engram.app.writeback.MediaWriteBack
import cam.engram.app.writeback.StripRepair
import java.io.File

/**
 * Manual dependency container: one place wires the app. A DI framework buys
 * nothing at this size and costs a compiler plugin (design principle: simplest
 * working solution).
 */
class AppContainer(
    context: Context,
    val db: EngramDb = EngramDb.build(context),
    val access: ContentAccess = ResolverContentAccess(context.contentResolver),
    val source: MediaSource = MediaStoreSource(context.contentResolver),
    val settings: SettingsStore = SettingsStore(context.applicationContext),
    val recorderFactory: VoiceRecorderFactory =
        object : VoiceRecorderFactory {
            override fun create() = MediaVoiceRecorder(context.applicationContext)
        },
) {
    val appContext: Context = context.applicationContext
    val scanner: RecordScanner = RecordScanner(access)
    val memoryReader: MemoryReader = MemoryReader(access)
    val reconciler: Reconciler =
        Reconciler(
            db = db,
            source = source,
            scanner = scanner,
            includeScreenshots = { kotlinx.coroutines.runBlocking { settings.current().includeScreenshots } },
            clock = System::currentTimeMillis,
        )
    val consentGate: ConsentGate = MediaStoreConsentGate(context.contentResolver)
    private val enrichmentGateway =
        EnrichmentGateway(
            settings = settings,
            gpsReader = ExifGpsReader(access),
            enricher = Enricher(GeocoderPlaceProvider(appContext), OpenMeteoWeatherProvider()),
        )
    val writeBack: MediaWriteBack =
        MediaWriteBack(
            db = db,
            access = access,
            scanner = scanner,
            backupDir = File(context.filesDir, "writeback"),
            enrichmentFor = { enrichmentGateway.recordFor(it) },
        )
    val stripRepair: StripRepair = StripRepair(db, writeBack)
}
