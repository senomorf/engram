package cam.engram.app.writeback

import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.db.MemoryFts
import cam.engram.app.data.db.RecordCacheEntity
import cam.engram.app.data.media.ContentAccess
import cam.engram.app.data.scan.RecordScanner
import cam.engram.format.jpeg.JpegCodec
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.mp4.Mp4Channels
import cam.engram.format.mp4.Mp4Files
import cam.engram.format.png.PngCodec
import cam.engram.format.png.PngEmbedder
import cam.engram.format.records.EngramRecord
import cam.engram.format.xmp.XmpCoreEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Transactional write-back (design sec 8): backup, write, verify by re-parse,
 * restore on failure. Invariants (review F3, F4): a crash or a failed write may
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
    ): WriteOutcome =
        withContext(io) {
            backupDir.mkdirs()
            val backup = File(backupDir, "${item.mediaId}.bak")
            writeSidecar(item)
            if (!access.copyToFile(item.uri, backup)) {
                return@withContext WriteOutcome.Failed("cannot back up original")
            }
            val attempt =
                runCatching {
                    if (item.isVideo) {
                        writeVideo(item, backup, records, mirrorText)
                    } else {
                        writePhoto(item, backup, records, mirrorText)
                    }
                }.getOrElse { e ->
                    // an exception mid-write may have left a partial file: restore
                    return@withContext rollback(item, backup, e.message ?: "write failed")
                }
            when (attempt) {
                // target was never modified, so cleanup without a needless restore
                is Attempt.Rejected -> {
                    cleanup(item.mediaId)
                    WriteOutcome.Failed("media write rejected")
                }
                is Attempt.Written ->
                    when (val outcome = attempt.outcome) {
                        is WriteOutcome.Success -> {
                            finishSuccess(item, outcome)
                            cleanup(item.mediaId)
                            outcome
                        }
                        is WriteOutcome.Failed -> rollback(item, backup, outcome.reason)
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

    // outcome of touching the media file: Rejected means it was never modified
    private sealed interface Attempt {
        data object Rejected : Attempt

        data class Written(
            val outcome: WriteOutcome,
        ) : Attempt
    }

    private fun writePhoto(
        item: MediaItemEntity,
        backup: File,
        records: List<EngramRecord>,
        mirrorText: String?,
    ): Attempt {
        val source = backup.readBytes()
        val engine = XmpCoreEngine()
        val out =
            if (item.mime == "image/png") {
                PngEmbedder(engine).embed(source, records, mirrorText)
            } else {
                JpegEmbedder(engine).embed(source, records, mirrorText)
            }
        if (!access.writeBytes(item.uri, out)) return Attempt.Rejected
        return Attempt.Written(verify(item))
    }

    private fun writeVideo(
        item: MediaItemEntity,
        backup: File,
        records: List<EngramRecord>,
        mirrorText: String?,
    ): Attempt {
        val rebuilt = File(backupDir, "${item.mediaId}.new.mp4")
        try {
            Mp4Files.appendRecords(backup, rebuilt, records, mirrorText)
            if (!access.writeFromFile(item.uri, rebuilt)) return Attempt.Rejected
        } finally {
            rebuilt.delete()
        }
        return Attempt.Written(verify(item))
    }

    private fun verify(item: MediaItemEntity): WriteOutcome {
        val outcome =
            scanner.scan(item.uri, item.isVideo, item.mime)
                ?: return WriteOutcome.Failed("verification could not read file back")
        if (outcome.recordCount == 0) return WriteOutcome.Failed("verification found no records after write")
        return WriteOutcome.Success(
            recordCount = outcome.recordCount,
            payloadLength = outcome.payloadLength,
            overSoftCap = outcome.payloadLength > SOFT_CAP_BYTES,
        )
    }

    private suspend fun finishSuccess(
        item: MediaItemEntity,
        result: WriteOutcome.Success,
    ) {
        val outcome = scanner.scan(item.uri, item.isVideo, item.mime)
        val size = access.withChannel(item.uri) { it.size() } ?: item.sizeBytes
        db.media().upsert(
            listOf(
                item.copy(
                    recordCount = result.recordCount,
                    payloadLength = result.payloadLength,
                    sizeBytes = size,
                    lastScanMillis = clock(),
                ),
            ),
        )
        outcome?.recordsBlob?.let { blob ->
            db.recordCache().upsert(
                RecordCacheEntity(
                    mediaId = item.mediaId,
                    identityTakenAt = item.takenAtMillis,
                    sizeBytesAtScan = size,
                    recordsBlob = blob,
                    recordCount = result.recordCount,
                    updatedMillis = clock(),
                ),
            )
        }
        val text = outcome?.searchableText.orEmpty()
        if (text.isBlank()) db.search().delete(item.mediaId) else db.search().upsert(MemoryFts(item.mediaId, text))
    }

    private fun restore(
        uri: String,
        backup: File,
    ): Boolean = backup.exists() && access.writeFromFile(uri, backup)

    private fun cleanup(mediaId: Long) {
        File(backupDir, "$mediaId.bak").delete()
        File(backupDir, "$mediaId.meta").delete()
    }

    private fun writeSidecar(item: MediaItemEntity) {
        File(backupDir, "${item.mediaId}.meta").writeText("${item.uri}\n${item.isVideo}\n${item.mime}")
    }

    /**
     * Startup safety net: for each lingering backup, restore the original only
     * if the target no longer parses as a valid container. A completed-and-
     * verified write parses fine and is kept, so a good write is never rolled
     * back (review F3).
     */
    suspend fun recoverPending() =
        withContext(io) {
            val backups = backupDir.listFiles { f -> f.extension == "bak" } ?: return@withContext
            for (backup in backups) {
                val mediaId = backup.nameWithoutExtension
                val meta = File(backupDir, "$mediaId.meta").takeIf { it.exists() }?.readLines()
                val uri = meta?.getOrNull(0)
                if (uri != null) {
                    val isVideo = meta.getOrNull(1)?.toBoolean() ?: false
                    val mime = meta.getOrNull(2) ?: "image/jpeg"
                    if (!targetParses(uri, isVideo, mime)) access.writeFromFile(uri, backup)
                }
                backup.delete()
                File(backupDir, "$mediaId.meta").delete()
            }
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
