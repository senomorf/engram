package cam.engram.app.writeback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Maintenance for backups shelved because their media id was reused for a different capture
 * (finding F1): the displaced photo's only remaining copy, renamed out of the recovery scan so
 * it is never written over the new photo. Left alone they are invisible and unbounded (issue
 * #92), so the Tools screen lists them and offers to save or discard.
 *
 * This is administration, not the write transaction, which is why it lives beside
 * [MediaWriteBack] rather than inside it (issue #129). It shares that class's [mutex] because
 * listing, copying, or deleting must never interleave with a write or a recovery pass touching
 * the same directory.
 */
class ShelvedBackups(
    private val journal: WriteJournal,
    private val mutex: Mutex,
    private val io: CoroutineDispatcher,
) {
    suspend fun list(): List<ShelvedBackup> =
        withContext(io) {
            mutex.withLock {
                journal.orphanBackups().map { ShelvedBackup(it.name, it.length()) }.sortedBy { it.name }
            }
        }

    /** Streams every shelved backup into [sink]; returns how many landed. */
    suspend fun copyTo(sink: ShelvedSink): Int =
        withContext(io) {
            mutex.withLock {
                journal.orphanBackups().count { file ->
                    // streamed, never read whole: a shelved backup can be a full-size video
                    runCatching {
                        sink.open(file.name)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
                    }.getOrDefault(false)
                }
            }
        }

    /** Deletes every shelved backup; returns how many were removed. */
    suspend fun discard(): Int =
        withContext(io) {
            mutex.withLock { journal.orphanBackups().count { it.delete() } }
        }
}
