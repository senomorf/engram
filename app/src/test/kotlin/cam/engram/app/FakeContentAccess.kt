package cam.engram.app

import cam.engram.app.data.media.ContentAccess
import cam.engram.app.data.media.WriteResult
import java.io.File
import java.io.FileInputStream
import java.nio.channels.SeekableByteChannel

/** In-memory ContentResolver stand-in shared by unit tests. */
class FakeContentAccess : ContentAccess {
    val files = mutableMapOf<String, ByteArray>()

    // capture identity (DATE_TAKEN) per uri; unset means null, so the reuse guard never fires
    // for tests that do not model a reused id
    val captureIdentity = mutableMapOf<String, Long>()
    var rejectWrites = false
    var corruptWrites = false

    // fail only restore (writeFromFile) while letting the corrupt writeBytes run,
    // to exercise the "restore also failed, keep the backup" path (review F4)
    var rejectRestore = false

    // restore opens the target but does not complete (OpenedUncertain): the journal stays
    // Unresolved, distinct from a NotOpened restore that needs consent (finding C2)
    var uncertainRestore = false

    // writeBytes truncates the target then reports the write did not complete: models a
    // disk-full / provider-death mid-write, which must route to restore (finding 2, mechanism 2)
    var failWriteAfterTruncate = false

    // copyToFile writes only a prefix then fails: models a partial backup (finding 2, mechanism 1)
    var partialCopyToFile = false

    // writeBytes reports Ok without changing the target: models a provider that lies about
    // the write landing, which only id-based verification can catch (finding B)
    var ignoreWrites = false

    // writeBytes lands the file but drops its last 12 bytes: models a png write cut off before its
    // terminal IEND chunk, a structurally incomplete file the verify must reject (finding F2)
    var truncateWrites = false

    // counts backup copies so a test can prove a retry reused the committed backup
    var copyToFileCount = 0

    override fun readBytes(uri: String): ByteArray? = files[uri]

    override fun readCaptureIdentity(uri: String): Long? = captureIdentity[uri]

    override fun <T> withChannel(
        uri: String,
        block: (SeekableByteChannel) -> T,
    ): T? {
        val bytes = files[uri] ?: return null
        val tmp = File.createTempFile("fake", ".bin")
        return try {
            tmp.writeBytes(bytes)
            FileInputStream(tmp).channel.use(block)
        } finally {
            tmp.delete()
        }
    }

    override fun writeBytes(
        uri: String,
        bytes: ByteArray,
    ): WriteResult {
        if (rejectWrites) return WriteResult.NotOpened
        if (failWriteAfterTruncate) {
            files[uri] = ByteArray(3) { 0x11 } // "wt" already truncated the target
            return WriteResult.OpenedUncertain
        }
        if (ignoreWrites) return WriteResult.Ok
        if (truncateWrites) {
            files[uri] = bytes.copyOfRange(0, maxOf(0, bytes.size - 12)) // cut before the terminal chunk
            return WriteResult.Ok
        }
        files[uri] = if (corruptWrites) ByteArray(4) { 0x11 } else bytes
        return WriteResult.Ok
    }

    override fun copyToFile(
        uri: String,
        target: File,
    ): Boolean {
        copyToFileCount++
        val bytes = files[uri] ?: return false
        if (partialCopyToFile) {
            target.writeBytes(bytes.copyOf(bytes.size / 2))
            return false
        }
        target.writeBytes(bytes)
        return true
    }

    override fun writeFromFile(
        uri: String,
        source: File,
    ): WriteResult {
        // restore path stays clean under failWriteAfterTruncate so an uncertain forward write
        // can still be rolled back; rejectRestore is the knob for a failing restore
        if (rejectWrites || rejectRestore) return WriteResult.NotOpened
        if (uncertainRestore) return WriteResult.OpenedUncertain
        files[uri] = source.readBytes()
        return WriteResult.Ok
    }
}
