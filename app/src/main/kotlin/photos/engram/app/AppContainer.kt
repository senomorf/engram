package photos.engram.app

import android.content.Context
import photos.engram.app.audio.MediaVoiceRecorder
import photos.engram.app.audio.VoiceRecorderFactory
import photos.engram.app.data.SettingsStore
import photos.engram.app.data.db.EngramDb
import photos.engram.app.data.media.ContentAccess
import photos.engram.app.data.media.MediaSource
import photos.engram.app.data.media.MediaStoreSource
import photos.engram.app.data.media.ResolverContentAccess
import photos.engram.app.data.scan.RecordScanner
import photos.engram.app.domain.MemoryReader
import photos.engram.app.domain.Reconciler
import photos.engram.app.writeback.ConsentGate
import photos.engram.app.writeback.MediaStoreConsentGate
import photos.engram.app.writeback.MediaWriteBack
import photos.engram.app.writeback.StripRepair
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
    val writeBack: MediaWriteBack =
        MediaWriteBack(
            db = db,
            access = access,
            scanner = scanner,
            backupDir = File(context.filesDir, "writeback"),
        )
    val stripRepair: StripRepair = StripRepair(db, writeBack)
}
