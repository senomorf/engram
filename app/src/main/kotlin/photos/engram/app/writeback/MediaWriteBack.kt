package photos.engram.app.writeback

import photos.engram.app.data.db.EngramDb
import photos.engram.app.data.db.MediaItemEntity
import photos.engram.app.data.db.RecordCacheEntity
import photos.engram.app.data.media.ContentAccess
import photos.engram.app.data.scan.RecordScanner
import photos.engram.format.jpeg.JpegEmbedder
import photos.engram.format.mp4.Mp4Files
import photos.engram.format.png.PngEmbedder
import photos.engram.format.records.EngramRecord
import photos.engram.format.xmp.XmpCoreEngine
import java.io.File

/**
 * Transactional write-back (design sec 8): backup, write, verify by re-parse,
 * restore on failure. A crash at any step may lose the pending note, never the
 * photo: the backup file plus its .uri sidecar allow recovery on next start.
 */
class MediaWriteBack(
    private val db: EngramDb,
    private val access: ContentAccess,
    private val scanner: RecordScanner,
    private val backupDir: File,
    private val recordFactory: RecordFactory = RecordFactory(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun write(
        item: MediaItemEntity,
        annotation: Annotation,
    ): WriteOutcome {
        val records = recordFactory.fromAnnotation(annotation)
        if (records.isEmpty()) return WriteOutcome.Failed("nothing to write")
        return writeRecords(item, records, annotation.noteText)
    }

    suspend fun writeRecords(
        item: MediaItemEntity,
        records: List<EngramRecord>,
        mirrorText: String?,
    ): WriteOutcome {
        backupDir.mkdirs()
        val backup = File(backupDir, "${item.mediaId}.bak")
        File(backupDir, "${item.mediaId}.uri").writeText(item.uri)
        if (!access.copyToFile(item.uri, backup)) {
            return WriteOutcome.Failed("cannot back up original")
        }
        val result =
            runCatching {
                if (item.isVideo) {
                    writeVideo(
                        item,
                        backup,
                        records,
                        mirrorText,
                    )
                } else {
                    writePhoto(item, backup, records, mirrorText)
                }
            }.getOrElse { e ->
                restore(item.uri, backup)
                return WriteOutcome.Failed(e.message ?: "write failed")
            }
        return when (result) {
            is WriteOutcome.Success -> {
                finishSuccess(item, result)
                cleanup(item.mediaId)
                result
            }
            is WriteOutcome.Failed -> {
                restore(item.uri, backup)
                cleanup(item.mediaId)
                result
            }
        }
    }

    private fun writePhoto(
        item: MediaItemEntity,
        backup: File,
        records: List<EngramRecord>,
        mirrorText: String?,
    ): WriteOutcome {
        val source = backup.readBytes()
        val engine = XmpCoreEngine()
        val out =
            if (item.mime == "image/png") {
                PngEmbedder(engine).embed(source, records, mirrorText)
            } else {
                JpegEmbedder(engine).embed(source, records, mirrorText)
            }
        if (!access.writeBytes(item.uri, out)) return WriteOutcome.Failed("media write rejected")
        return verify(item)
    }

    private fun writeVideo(
        item: MediaItemEntity,
        backup: File,
        records: List<EngramRecord>,
        mirrorText: String?,
    ): WriteOutcome {
        val rebuilt = File(backupDir, "${item.mediaId}.new.mp4")
        try {
            Mp4Files.appendRecords(backup, rebuilt, records, mirrorText)
            if (!access.writeFromFile(item.uri, rebuilt)) return WriteOutcome.Failed("media write rejected")
        } finally {
            rebuilt.delete()
        }
        return verify(item)
    }

    private fun verify(item: MediaItemEntity): WriteOutcome {
        val outcome =
            scanner.scan(item.uri, item.isVideo, item.mime)
                ?: return WriteOutcome.Failed("verification could not read file back")
        if (outcome.recordCount == 0) return WriteOutcome.Failed("verification found no records after write")
        return WriteOutcome.Success(outcome.recordCount, outcome.payloadLength)
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
                    sizeBytesAtScan = size,
                    recordsBlob = blob,
                    recordCount = result.recordCount,
                    updatedMillis = clock(),
                ),
            )
        }
    }

    private fun restore(
        uri: String,
        backup: File,
    ) {
        if (backup.exists()) access.writeFromFile(uri, backup)
    }

    private fun cleanup(mediaId: Long) {
        File(backupDir, "$mediaId.bak").delete()
        File(backupDir, "$mediaId.uri").delete()
    }

    /** Startup safety net: restore any backup whose target no longer parses. */
    fun recoverPending() {
        val backups = backupDir.listFiles { f -> f.extension == "bak" } ?: return
        for (backup in backups) {
            val mediaId = backup.nameWithoutExtension
            val uriFile = File(backupDir, "$mediaId.uri")
            val uri = uriFile.takeIf { it.exists() }?.readText()
            if (uri != null) {
                val readable = access.readBytes(uri) != null || access.withChannel(uri) { true } == true
                if (readable) access.writeFromFile(uri, backup)
            }
            backup.delete()
            uriFile.delete()
        }
    }
}
