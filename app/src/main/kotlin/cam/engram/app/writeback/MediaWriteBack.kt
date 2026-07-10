package cam.engram.app.writeback

import androidx.room.withTransaction
import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.db.MemoryFts
import cam.engram.app.data.db.RecordCacheEntity
import cam.engram.app.data.media.ContentAccess
import cam.engram.app.data.media.WriteResult
import cam.engram.app.data.scan.RecordScanner
import cam.engram.app.data.scan.ScanOutcome
import cam.engram.format.jpeg.JpegCodec
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.mp4.Mp4Channels
import cam.engram.format.mp4.Mp4Files
import cam.engram.format.png.PngCodec
import cam.engram.format.png.PngEmbedder
import cam.engram.format.records.EngramRecord
import cam.engram.format.xmp.XmpCoreEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Transactional write-back (design sec 8): backup, write, verify the records
 * landed, restore on failure. Invariants (review F3, F4): a crash or a failed write may
 * lose the pending note, never the photo. The backup survives until either the
 * new file verifies intact or the original is restored; if restore itself fails
 * the backup is kept for [recoverPending] on next start.
 */
class MediaWriteBack(
    private val db: EngramDb,
    private val access: ContentAccess,
    private val scanner: RecordScanner,
    private val backupDir: File,
    private val io: CoroutineDispatcher,
    private val recordFactory: RecordFactory = RecordFactory(),
    // pre-fetched context record read from cache (review F5): instant, no network
    private val cachedEnrichment: suspend (MediaItemEntity) -> EngramRecord? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // serialize writes and recovery so a foreground save and a background recoverPending
    // never interleave backup/restore/cleanup on the same media id (finding 2)
    private val mutex = Mutex()

    suspend fun write(
        item: MediaItemEntity,
        annotation: Annotation,
    ): WriteOutcome {
        val records = recordFactory.fromAnnotation(annotation).toMutableList()
        if (records.isEmpty()) return WriteOutcome.Failed("nothing to write")
        cachedEnrichment(item)?.let { records += it }
        return writeRecords(item, records, annotation.noteText)
    }

    suspend fun writeRecords(
        item: MediaItemEntity,
        records: List<EngramRecord>,
        mirrorText: String?,
        carryFrames: List<ByteArray> = emptyList(),
    ): WriteOutcome =
        withContext(io) {
            mutex.withLock {
                backupDir.mkdirs()
                val backup = File(backupDir, "${item.mediaId}.bak")
                writeSidecar(item, records)
                // publish the backup atomically (copy to tmp, fsync inside copyToFile, rename) and
                // never overwrite a committed one, so a partial copy is never restored over an intact
                // original and a retry reuses the pristine copy instead of re-reading the corrupt
                // target (finding 2)
                if (!backup.exists()) {
                    val tmp = File(backupDir, "${item.mediaId}.bak.tmp")
                    if (!access.copyToFile(item.uri, tmp) || !tmp.renameTo(backup)) {
                        tmp.delete()
                        return@withLock WriteOutcome.Failed("cannot back up original")
                    }
                }
                val attempt =
                    runCatching {
                        if (item.isVideo) {
                            writeVideo(item, backup, records, mirrorText, carryFrames)
                        } else {
                            writePhoto(item, backup, records, mirrorText, carryFrames)
                        }
                    }.getOrElse { e ->
                        // an exception mid-write may have left a partial file: restore
                        return@withLock rollback(item, backup, e.message ?: "write failed")
                    }
                when (attempt) {
                    // the stream never opened, so the target is untouched: cleanup, no restore
                    is Attempt.Rejected -> {
                        cleanup(item.mediaId)
                        WriteOutcome.Failed("media write rejected")
                    }
                    is Attempt.Failed -> rollback(item, backup, attempt.reason)
                    is Attempt.Verified -> {
                        finishSuccess(item, attempt.outcome, attempt.scan)
                        cleanup(item.mediaId)
                        attempt.outcome
                    }
                }
            }
        }

    // restore the original; only clear the backup once the photo is safe again
    private fun rollback(
        item: MediaItemEntity,
        backup: File,
        reason: String,
    ): WriteOutcome =
        if (restore(item.uri, backup)) {
            cleanup(item.mediaId)
            WriteOutcome.Failed(reason)
        } else {
            // keep the backup for recoverPending; do not delete the only pristine copy
            WriteOutcome.Failed("$reason; original preserved in backup, will restore on restart")
        }

    // outcome of touching the media file: Rejected means it was never modified; Failed
    // restores from backup; Verified carries the one scan behind the success verdict
    private sealed interface Attempt {
        data object Rejected : Attempt

        data class Failed(
            val reason: String,
        ) : Attempt

        data class Verified(
            val outcome: WriteOutcome.Success,
            val scan: ScanOutcome,
        ) : Attempt
    }

    private fun writePhoto(
        item: MediaItemEntity,
        backup: File,
        records: List<EngramRecord>,
        mirrorText: String?,
        carryFrames: List<ByteArray>,
    ): Attempt {
        val source = backup.readBytes()
        val engine = XmpCoreEngine()
        val out =
            if (item.mime == "image/png") {
                PngEmbedder(engine).embed(source, records, mirrorText, carryFrames)
            } else {
                JpegEmbedder(engine).embed(source, records, mirrorText, carryFrames)
            }
        return when (access.writeBytes(item.uri, out)) {
            WriteResult.NotOpened -> Attempt.Rejected
            // the target was truncated but the write did not finish: restore from backup
            WriteResult.OpenedUncertain -> Attempt.Failed("write did not complete")
            WriteResult.Ok -> verify(item)
        }
    }

    private fun writeVideo(
        item: MediaItemEntity,
        backup: File,
        records: List<EngramRecord>,
        mirrorText: String?,
        carryFrames: List<ByteArray>,
    ): Attempt {
        val rebuilt = File(backupDir, "${item.mediaId}.new.mp4")
        return try {
            Mp4Files.appendRecords(backup, rebuilt, records, mirrorText, carryFrames)
            when (access.writeFromFile(item.uri, rebuilt)) {
                WriteResult.NotOpened -> Attempt.Rejected
                WriteResult.OpenedUncertain -> Attempt.Failed("write did not complete")
                WriteResult.Ok -> verify(item)
            }
        } finally {
            rebuilt.delete()
        }
    }

    // one scan serves both the success verdict and the index rows, so what was
    // verified is exactly what gets persisted (a second scan could silently diverge)
    private fun verify(item: MediaItemEntity): Attempt {
        val scan =
            scanner.scan(item.uri, item.isVideo, item.mime)
                ?: return Attempt.Failed("verification could not read file back")
        if (scan.recordCount == 0) return Attempt.Failed("verification found no records after write")
        val outcome =
            WriteOutcome.Success(
                recordCount = scan.recordCount,
                payloadLength = scan.payloadLength,
                overSoftCap = scan.payloadLength > SOFT_CAP_BYTES,
            )
        return Attempt.Verified(outcome, scan)
    }

    private suspend fun finishSuccess(
        item: MediaItemEntity,
        result: WriteOutcome.Success,
        scan: ScanOutcome,
    ) {
        val size = access.withChannel(item.uri) { it.size() } ?: item.sizeBytes
        val row =
            item.copy(
                recordCount = result.recordCount,
                payloadLength = result.payloadLength,
                sizeBytes = size,
                lastScanMillis = clock(),
            )
        val cacheRow =
            scan.recordsBlob?.let { blob ->
                RecordCacheEntity(
                    mediaId = item.mediaId,
                    identityTakenAt = item.takenAtMillis,
                    sizeBytesAtScan = size,
                    recordsBlob = blob,
                    recordCount = result.recordCount,
                    updatedMillis = clock(),
                    originalName = item.relativePath,
                    // the scanner already content-addressed the media (no extra read), so a
                    // later cache orphan can still export (finding 9)
                    contentHash = scan.contentHash,
                )
            }
        val text = scan.searchableText
        // the media row, the non-rebuildable record cache, and the search index commit
        // together: a crash or a failed insert between them can no longer leave a media
        // row claiming records the cache never received (D3)
        db.withTransaction {
            db.media().upsert(listOf(row))
            cacheRow?.let { db.recordCache().upsert(it) }
            if (text.isBlank()) db.search().delete(item.mediaId) else db.search().upsert(MemoryFts(item.mediaId, text))
        }
    }

    private fun restore(
        uri: String,
        backup: File,
    ): Boolean = backup.exists() && access.writeFromFile(uri, backup) == WriteResult.Ok

    private fun cleanup(mediaId: Long) {
        File(backupDir, "$mediaId.bak").delete()
        File(backupDir, "$mediaId.meta").delete()
    }

    private fun writeSidecar(
        item: MediaItemEntity,
        records: List<EngramRecord>,
    ) {
        // the expected record ids let recoverPending tell a finished write from an
        // interrupted one, instead of trusting a bare container parse (finding A)
        val ids = records.joinToString(",") { it.idHex }
        val content = "${item.uri}\n${item.isVideo}\n${item.mime}\n$ids"
        val meta = File(backupDir, "${item.mediaId}.meta")
        val tmp = File(backupDir, "${item.mediaId}.meta.tmp")
        // durable (fsync + rename) so the backup is never published before its sidecar exists
        tmp.outputStream().use {
            it.write(content.encodeToByteArray())
            it.fd.sync()
        }
        tmp.renameTo(meta)
    }

    /**
     * Startup safety net: for each lingering backup, restore the original unless
     * the target actually carries every record the write meant to add (verified
     * by id and CRC, [writeCompleted]). A parseable container that lost its
     * records is treated as an interrupted write and rolled back, so the only
     * pristine copy is never dropped on a crash mid-write (finding A, review F3).
     */
    suspend fun recoverPending() =
        withContext(io) {
            mutex.withLock {
                val backups = backupDir.listFiles { f -> f.extension == "bak" } ?: return@withLock
                for (backup in backups) {
                    if (recoverBackup(backup)) {
                        backup.delete()
                        File(backupDir, "${backup.nameWithoutExtension}.meta").delete()
                    }
                }
            }
        }

    // true when the backup can be dropped: the target already carries the expected records,
    // or the original was restored. false keeps it for the next startup (no readable sidecar,
    // or a restore that could not complete) so the only pristine copy is never lost (finding 2)
    private fun recoverBackup(backup: File): Boolean {
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
        return writeCompleted(uri, isVideo, mime, expectedIds) || restore(uri, backup)
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
            targetParses(uri, isVideo, mime)
        } else {
            scanner.presentIds(uri, isVideo, mime).containsAll(expectedIds)
        }

    private fun targetParses(
        uri: String,
        isVideo: Boolean,
        mime: String,
    ): Boolean =
        runCatching {
            if (isVideo) {
                access.withChannel(uri) { Mp4Channels.topLevel(it) } != null
            } else {
                val bytes = access.readBytes(uri) ?: return false
                if (mime == "image/png") PngCodec.parse(bytes) else JpegCodec.parse(bytes)
                true
            }
        }.getOrDefault(false)
}
