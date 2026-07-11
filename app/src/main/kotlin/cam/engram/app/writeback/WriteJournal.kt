package cam.engram.app.writeback

import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.media.ContentAccess
import cam.engram.app.data.media.WriteResult
import cam.engram.app.data.scan.RecordScanner
import cam.engram.format.Digests
import cam.engram.format.jpeg.JpegCodec
import cam.engram.format.mp4.Mp4Channels
import cam.engram.format.png.PngCodec
import java.io.File
import java.io.FileInputStream

/**
 * The durable side of the write-back transaction (design D26): the .meta sidecar
 * (written and fsynced before the backup exists), the .bak publication, and the
 * resolution of a lingering pair. Resolution drops a journal only once the target
 * provably needs nothing from it: the write completed (every expected record id
 * present), the target still equals the backup byte for byte (a failure before
 * the first write; settled without any write grant), or the original was
 * restored. Callers serialize access with their own mutex.
 */
class WriteJournal(
    private val backupDir: File,
    private val access: ContentAccess,
    private val scanner: RecordScanner,
) {
    fun backupFor(mediaId: Long): File = File(backupDir, "$mediaId.bak")

    fun pendingBackups(): List<File> = backupDir.listFiles { f -> f.extension == "bak" }?.toList().orEmpty()

    fun writeSidecar(
        item: MediaItemEntity,
        expectedIds: Set<String>,
    ) {
        // the expected record ids let recovery tell a finished write from an
        // interrupted one, instead of trusting a bare container parse (finding A)
        val content = "${item.uri}\n${item.isVideo}\n${item.mime}\n${expectedIds.joinToString(",")}"
        val meta = File(backupDir, "${item.mediaId}.meta")
        val tmp = File(backupDir, "${item.mediaId}.meta.tmp")
        // durable (fsync + rename) so the backup is never published before its sidecar exists
        tmp.outputStream().use {
            it.write(content.encodeToByteArray())
            it.fd.sync()
        }
        tmp.renameTo(meta)
    }

    // publish the backup atomically (copy to tmp, fsync inside copyToFile, rename) and
    // never overwrite a committed one, so a partial copy is never restored over an
    // intact original (finding 2); callers resolve first, the exists guard is a belt
    fun publishBackup(item: MediaItemEntity): Boolean {
        val backup = backupFor(item.mediaId)
        if (backup.exists()) return true
        val tmp = File(backupDir, "${item.mediaId}.bak.tmp")
        if (!access.copyToFile(item.uri, tmp) || !tmp.renameTo(backup)) {
            tmp.delete()
            return false
        }
        return true
    }

    // settle one lingering transaction: the pair is dropped only once the target already
    // carries the expected records (write completed), still equals the backup (nothing
    // ever landed), or the original was restored. false keeps both for a later attempt
    // or startup so the only pristine copy is never lost (finding 2)
    fun resolve(backup: File): Boolean {
        val meta = File(backupDir, "${backup.nameWithoutExtension}.meta").takeIf { it.exists() }?.readLines()
        val uri = meta?.getOrNull(0) ?: return false
        val isVideo = meta.getOrNull(1)?.toBoolean() ?: false
        val mime = meta.getOrNull(2) ?: "image/jpeg"
        val expectedIds =
            meta
                .getOrNull(3)
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
        val settled =
            writeCompleted(uri, isVideo, mime, expectedIds) ||
                targetMatchesBackup(uri, backup) ||
                restore(uri, backup)
        if (!settled) return false
        cleanup(backup.nameWithoutExtension.toLongOrNull() ?: return false)
        return true
    }

    fun restore(
        uri: String,
        backup: File,
    ): Boolean = backup.exists() && access.writeFromFile(uri, backup) == WriteResult.Ok

    fun cleanup(mediaId: Long) {
        File(backupDir, "$mediaId.bak").delete()
        File(backupDir, "$mediaId.meta").delete()
    }

    // a journal whose target still equals its backup byte for byte is residue of a
    // failure before the first write (crash mid-preparation): nothing to restore,
    // and crucially no write grant is needed to settle it
    private fun targetMatchesBackup(
        uri: String,
        backup: File,
    ): Boolean {
        val target = access.withChannel(uri) { Digests.sha256Hex(it) } ?: return false
        val kept = FileInputStream(backup).channel.use { Digests.sha256Hex(it) }
        return target == kept
    }

    // a write finished only if the target carries every expected record with a valid
    // CRC; a legacy sidecar without ids falls back to a bare container parse
    private fun writeCompleted(
        uri: String,
        isVideo: Boolean,
        mime: String,
        expectedIds: Set<String>,
    ): Boolean =
        if (expectedIds.isEmpty()) {
            runCatching {
                if (isVideo) {
                    access.withChannel(uri) { Mp4Channels.topLevel(it) } != null
                } else {
                    val bytes = access.readBytes(uri) ?: return false
                    if (mime == "image/png") PngCodec.parse(bytes) else JpegCodec.parse(bytes)
                    true
                }
            }.getOrDefault(false)
        } else {
            scanner.presentIds(uri, isVideo, mime).containsAll(expectedIds)
        }
}
