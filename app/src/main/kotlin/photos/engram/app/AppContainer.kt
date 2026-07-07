package photos.engram.app

import android.content.Context
import photos.engram.app.data.db.EngramDb
import photos.engram.app.data.media.ContentAccess
import photos.engram.app.data.media.MediaSource
import photos.engram.app.data.media.MediaStoreSource
import photos.engram.app.data.media.ResolverContentAccess
import photos.engram.app.data.scan.RecordScanner
import photos.engram.app.domain.Reconciler

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
) {
    val scanner: RecordScanner = RecordScanner(access)
    val reconciler: Reconciler =
        Reconciler(
            db = db,
            source = source,
            scanner = scanner,
            includeScreenshots = { true },
            clock = System::currentTimeMillis,
        )
}
