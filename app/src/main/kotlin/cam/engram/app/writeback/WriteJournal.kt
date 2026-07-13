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
        // the expected record ids let recovery tell a finished write from an interrupted one
        // (finding A); the capture identity (takenAtMillis) lets it tell a partial write of the
        // original from a reused MediaStore id now holding a different photo (finding F1)
        val content =
            "${item.uri}\n${item.isVideo}\n${item.mime}\n${expectedIds.joinToString(",")}\n${item.takenAtMillis}"
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

    // the outcome of settling one lingering transaction
    sealed interface Resolution {
        // dropped: the target needs nothing from the journal (write completed, target still
        // equals the backup, or the original was restored)
        data object Settled : Resolution

        // the target must be restored but the write could not open it: a MediaStore write
        // grant is Activity-bound and not persistable (design D26), so the user's consent is
        // needed. [uri] identifies the target to request it for (finding C2)
        data class NeedsConsent(
            val uri: String,
        ) : Resolution

        // could not settle for another reason: keep the pair for a later attempt or startup
        // so the only pristine copy is never lost (finding 2)
        data object Unresolved : Resolution
    }

    // settle one lingering transaction: the pair is dropped (Settled) only once the target
    // already carries the expected records (write completed), still equals the backup (nothing
    // ever landed), or the original was restored. A restore that cannot open the target
    // surfaces NeedsConsent so recovery can re-request the grant instead of dead-ending. A target
    // whose capture identity no longer matches (a reused id) is orphaned, never overwritten (F1).
    fun resolve(backup: File): Resolution {
        val mediaId = backup.nameWithoutExtension.toLongOrNull() ?: return Resolution.Unresolved
        val meta = File(backupDir, "${backup.nameWithoutExtension}.meta").takeIf { it.exists() }?.readLines()
        val uri = meta?.getOrNull(0) ?: return Resolution.Unresolved
        val isVideo = meta.getOrNull(1)?.toBoolean() ?: false
        val mime = meta.getOrNull(2) ?: "image/jpeg"
        val expectedIds =
            meta
                .getOrNull(3)
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
        val expectedIdentity = meta.getOrNull(4)?.toLongOrNull()
        if (writeCompleted(uri, isVideo, mime, expectedIds) || targetMatchesBackup(uri, backup)) {
            cleanup(mediaId)
            return Resolution.Settled
        }
        // a reused MediaStore id now points at a different capture: restoring our backup would
        // overwrite an unrelated photo. Capture identity (DATE_TAKEN) survives a partial write of
        // the original but differs for a reused target, so a positive mismatch means orphan the
        // backup (kept on disk, out of the *.bak scan), never write it over the new photo (F1)
        if (expectedIdentity != null) {
            val current = access.readCaptureIdentity(uri)
            if (current != null && current != expectedIdentity) {
                orphan(mediaId)
                return Resolution.Settled
            }
        }
        return when (restore(uri, backup)) {
            WriteResult.Ok -> {
                cleanup(mediaId)
                Resolution.Settled
            }
            WriteResult.NotOpened -> Resolution.NeedsConsent(uri)
            WriteResult.OpenedUncertain -> Resolution.Unresolved
        }
    }

    fun restore(
        uri: String,
        backup: File,
    ): WriteResult = if (backup.exists()) access.writeFromFile(uri, backup) else WriteResult.OpenedUncertain

    fun cleanup(mediaId: Long) {
        File(backupDir, "$mediaId.bak").delete()
        File(backupDir, "$mediaId.meta").delete()
    }

    // a reused-target backup must not be written over the new photo, nor deleted (it is the old
    // capture's only copy): rename it out of the *.bak recovery scan and drop the sidecar so
    // resolve stops retrying it (finding F1)
    fun orphan(mediaId: Long) {
        File(backupDir, "$mediaId.bak").renameTo(File(backupDir, "$mediaId.bak.orphan"))
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
