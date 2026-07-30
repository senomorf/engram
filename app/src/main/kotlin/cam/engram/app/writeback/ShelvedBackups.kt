package cam.engram.app.writeback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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

    /**
     * Streams every shelved backup into [sink]; returns how many landed. Each copy is named
     * and typed by what it actually is, so the user gets a photo or video they can open
     * rather than the internal .bak.orphan bookkeeping name (issue #92).
     */
    suspend fun copyTo(sink: ShelvedSink): Int =
        withContext(io) {
            mutex.withLock {
                journal.orphanBackups().count { file ->
                    // streamed, never read whole: a shelved backup can be a full-size video
                    runCatching {
                        val kind = MediaKind.of(file)
                        val name = "engram-recovered-${file.name.substringBefore(".bak.orphan")}.${kind.extension}"
                        sink.open(name, kind.mimeType)?.use { out ->
                            file.inputStream().use { it.copyTo(out) }
                        } != null
                    }.getOrDefault(false)
                }
            }
        }

    /**
     * What a shelved backup actually is, read from its leading bytes. The journal stores only
     * bookkeeping names, and the sidecar that knew the mime is deleted when a backup is
     * shelved, so the container has to be recognized from the file itself.
     */
    private enum class MediaKind(
        val extension: String,
        val mimeType: String,
    ) {
        JPEG("jpg", "image/jpeg"),
        PNG("png", "image/png"),
        MP4("mp4", "video/mp4"),
        UNKNOWN("bin", "application/octet-stream"),
        ;

        companion object {
            private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

            fun of(file: File): MediaKind {
                val head = ByteArray(12)
                val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
                if (read < head.size) return UNKNOWN
                return when {
                    head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> JPEG
                    head.copyOfRange(0, 4).contentEquals(PNG_SIGNATURE) -> PNG
                    // an mp4 declares its brand in the ftyp box right after the size field
                    head.copyOfRange(4, 8).decodeToString() == "ftyp" -> MP4
                    else -> UNKNOWN
                }
            }
        }
    }

    /** Deletes every shelved backup; returns how many were removed. */
    suspend fun discard(): Int =
        withContext(io) {
            mutex.withLock { journal.orphanBackups().count { it.delete() } }
        }
}
