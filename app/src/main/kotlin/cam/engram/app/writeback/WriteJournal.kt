package cam.engram.app.writeback

import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.media.ContentAccess
import cam.engram.app.data.media.WriteResult
import cam.engram.app.data.scan.RecordScanner
import cam.engram.format.Digests
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

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
    /** The exact bytes a write means to land: what verify and recovery hold the target to. */
    class PreparedOutput(
        val digestHex: String,
        val sizeBytes: Long,
    )

    fun backupFor(mediaId: Long): File = File(backupDir, "$mediaId.bak")

    fun pendingBackups(): List<File> = backupDir.listFiles { f -> f.extension == "bak" }?.toList().orEmpty()

    fun writeSidecar(
        item: MediaItemEntity,
        expectedIds: Set<String>,
        prepared: PreparedOutput? = null,
    ): Boolean {
        // the expected record ids let recovery tell a finished write from an interrupted one
        // (finding A); the capture identity (takenAtMillis) lets it tell a partial write of the
        // original from a reused MediaStore id now holding a different photo (finding F1); the
        // prepared output's digest and size, amended once the output exists, let both recovery
        // and verify demand the exact bytes the write meant to land (issue #97)
        val content =
            buildString {
                append("${item.uri}\n${item.isVideo}\n${item.mime}\n")
                append("${expectedIds.joinToString(",")}\n${item.takenAtMillis}")
                if (prepared != null) append("\n${prepared.digestHex}\n${prepared.sizeBytes}")
            }
        val meta = File(backupDir, "${item.mediaId}.meta")
        val tmp = File(backupDir, "${item.mediaId}.meta.tmp")
        // durable (fsync + rename, then a directory fsync) so the backup is never published
        // before its sidecar exists; a failed rename must fail the write (review N1 minor):
        // a backup without its sidecar would be unrecoverable residue
        tmp.outputStream().use {
            it.write(content.encodeToByteArray())
            it.fd.sync()
        }
        val ok = tmp.renameTo(meta)
        if (ok) fsyncDir() else tmp.delete()
        return ok
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
        fsyncDir()
        return true
    }

    // file fsync alone does not make the rename itself durable: sync the directory entry
    // too, best-effort (a failure only weakens durability, never the write)
    private fun fsyncDir() {
        runCatching {
            FileChannel.open(backupDir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
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
        val prepared =
            meta.getOrNull(5)?.takeIf { it.isNotBlank() }?.let { digest ->
                meta.getOrNull(6)?.toLongOrNull()?.let { size -> PreparedOutput(digest, size) }
            }
        if (writeCompleted(uri, isVideo, mime, expectedIds, prepared) || targetMatchesBackup(uri, backup)) {
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
                return if (orphan(mediaId, expectedIdentity)) Resolution.Settled else Resolution.Unresolved
            }
        }
        return when (restore(uri, backup)) {
            // an Ok restore is only trusted once the target's bytes equal the backup: a provider
            // that reports success while writing something else must not cost the only pristine
            // copy (issue #97). A mismatch keeps the journal for a later attempt.
            WriteResult.Ok ->
                if (targetMatchesBackup(uri, backup)) {
                    cleanup(mediaId)
                    Resolution.Settled
                } else {
                    Resolution.Unresolved
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
    // resolve stops retrying it (finding F1). The orphan name is unique per event (id, journal
    // identity, counter) and never renamed onto an existing file: a second reuse of the same id
    // must not destroy the first orphan, which is that capture's only copy (review N1)
    fun orphan(
        mediaId: Long,
        expectedIdentity: Long,
    ): Boolean {
        val backup = File(backupDir, "$mediaId.bak")
        var n = 0
        var target = File(backupDir, "$mediaId.$expectedIdentity.$n.bak.orphan")
        while (target.exists()) {
            n++
            target = File(backupDir, "$mediaId.$expectedIdentity.$n.bak.orphan")
        }
        if (!backup.renameTo(target)) return false
        fsyncDir()
        File(backupDir, "$mediaId.meta").delete()
        return true
    }

    // shelved reused-id backups (finding F1 / issue #92): preserved on disk, out of the
    // recovery scan, for a future surfacing affordance
    fun orphanBackups(): List<File> = backupDir.listFiles { f -> f.name.endsWith(".bak.orphan") }?.toList().orEmpty()

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

    /**
     * Did the write land? Three bars, strongest first:
     *  - the sidecar carries the prepared output's digest and size (issue #97): the target must
     *    equal those bytes exactly. This is the only bar that also catches a provider that wrote
     *    something transformed but record-complete.
     *  - ids only (a pre-digest sidecar): every expected record present, CRC-valid, in a
     *    structurally complete container (finding F2).
     *  - neither (a legacy sidecar): never provable. Parseability was accepted here before
     *    (issue #96), but a signature-only PNG parses and an empty MP4 yields a non-null box
     *    list, so a crash-truncated target settled and its pristine backup was deleted. Such a
     *    journal now falls through to the digest compare against the backup, else a restore.
     */
    private fun writeCompleted(
        uri: String,
        isVideo: Boolean,
        mime: String,
        expectedIds: Set<String>,
        prepared: PreparedOutput?,
    ): Boolean {
        if (prepared != null) return targetMatchesPrepared(uri, prepared)
        if (expectedIds.isEmpty()) return false
        val scan = scanner.scan(uri, isVideo, mime) ?: return false
        return scan.structurallyComplete && scan.presentIds.containsAll(expectedIds)
    }

    // the target is byte-for-byte the output the write prepared
    private fun targetMatchesPrepared(
        uri: String,
        prepared: PreparedOutput,
    ): Boolean {
        val size = access.withChannel(uri) { it.size() } ?: return false
        if (size != prepared.sizeBytes) return false
        return access.withChannel(uri) { Digests.sha256Hex(it) } == prepared.digestHex
    }
}
