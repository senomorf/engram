package cam.engram.app.writeback

import androidx.room.withTransaction
import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.db.MemoryFts
import cam.engram.app.data.db.RecordCacheEntity
import cam.engram.app.data.db.upsertSuperset
import cam.engram.app.data.media.ContentAccess
import cam.engram.app.data.media.WriteResult
import cam.engram.app.data.scan.RecordScanner
import cam.engram.app.data.scan.ScanOutcome
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.mp4.Mp4Files
import cam.engram.format.png.PngEmbedder
import cam.engram.format.records.EngramRecord
import cam.engram.format.toHex
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
    private val journal = WriteJournal(backupDir, access, scanner)

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
                val backup = journal.backupFor(item.mediaId)
                // a lingering pair from an earlier attempt is an unresolved transaction: the
                // target may be damaged and this .bak its only pristine copy. Settle it first
                // (recover or restore), before this attempt's sidecar clobbers the old journal;
                // refuse to write over unresolved state (finding A)
                if (backup.exists()) {
                    when (journal.resolve(backup)) {
                        is WriteJournal.Resolution.Settled -> Unit
                        // the pending restore needs the user's write grant: surface it as
                        // NotOpened so the UI requests consent, the same path a fresh save takes
                        is WriteJournal.Resolution.NeedsConsent -> return@withLock WriteOutcome.NotOpened
                        is WriteJournal.Resolution.Unresolved ->
                            return@withLock WriteOutcome.Failed(
                                "previous write unresolved; original preserved in backup, will restore on restart",
                            )
                    }
                }
                val expectedIds = expectedIdHexes(records, carryFrames)
                journal.writeSidecar(item, expectedIds)
                if (!journal.publishBackup(item)) {
                    return@withLock WriteOutcome.Failed("cannot back up original")
                }
                // preparation only reads (the backup, temp files): a failure here leaves the
                // target untouched by construction, so the journal is discarded, never restored
                // (restore itself opens the target with truncation and must not run needlessly)
                val prepared =
                    runCatching { prepare(item, backup, records, mirrorText, carryFrames) }
                        .getOrElse { e ->
                            journal.cleanup(item.mediaId)
                            return@withLock WriteOutcome.Failed(e.message ?: "write preparation failed")
                        }
                val attempt =
                    runCatching { commit(item, prepared, expectedIds) }
                        .getOrElse { e ->
                            // an exception mid-write may have left a partial file: restore
                            return@withLock rollback(item, backup, e.message ?: "write failed")
                        }
                when (attempt) {
                    // the stream never opened and any prior transaction was resolved above, so
                    // the target is genuinely untouched: cleanup, no restore
                    is Attempt.Rejected -> {
                        journal.cleanup(item.mediaId)
                        WriteOutcome.NotOpened
                    }
                    is Attempt.Failed -> rollback(item, backup, attempt.reason)
                    is Attempt.Verified -> {
                        finishSuccess(item, attempt.outcome, attempt.scan)
                        journal.cleanup(item.mediaId)
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
        if (journal.restore(item.uri, backup) == WriteResult.Ok) {
            journal.cleanup(item.mediaId)
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

    // the outputs a write needs, fully built from the backup before the target is opened
    private sealed interface Prepared {
        class Photo(
            val bytes: ByteArray,
        ) : Prepared

        class Video(
            val temp: File,
        ) : Prepared
    }

    // reads only: the backup and a temp file. Every guard that can refuse a write
    // (motion photo, unsafe layout, oversized metadata, malformed container) throws
    // here, where the target is still untouched
    private fun prepare(
        item: MediaItemEntity,
        backup: File,
        records: List<EngramRecord>,
        mirrorText: String?,
        carryFrames: List<ByteArray>,
    ): Prepared =
        if (item.isVideo) {
            val rebuilt = File(backupDir, "${item.mediaId}.new.mp4")
            runCatching { Mp4Files.appendRecords(backup, rebuilt, records, mirrorText, carryFrames) }
                .onFailure { rebuilt.delete() }
                .getOrThrow()
            Prepared.Video(rebuilt)
        } else {
            val source = backup.readBytes()
            val engine = XmpCoreEngine()
            Prepared.Photo(
                if (item.mime == "image/png") {
                    PngEmbedder(engine).embed(source, records, mirrorText, carryFrames)
                } else {
                    JpegEmbedder(engine).embed(source, records, mirrorText, carryFrames)
                },
            )
        }

    private fun commit(
        item: MediaItemEntity,
        prepared: Prepared,
        expectedIds: Set<String>,
    ): Attempt {
        val result =
            when (prepared) {
                is Prepared.Photo -> access.writeBytes(item.uri, prepared.bytes)
                is Prepared.Video ->
                    try {
                        access.writeFromFile(item.uri, prepared.temp)
                    } finally {
                        prepared.temp.delete()
                    }
            }
        return when (result) {
            WriteResult.NotOpened -> Attempt.Rejected
            // the target was truncated but the write did not finish: restore from backup
            WriteResult.OpenedUncertain -> Attempt.Failed("write did not complete")
            WriteResult.Ok -> verify(item, expectedIds)
        }
    }

    // one scan serves both the success verdict and the index rows, so what was
    // verified is exactly what gets persisted (a second scan could silently diverge)
    private fun verify(
        item: MediaItemEntity,
        expected: Set<String>,
    ): Attempt {
        val scan =
            scanner.scan(item.uri, item.isVideo, item.mime)
                ?: return Attempt.Failed("verification could not read file back")
        if (scan.recordCount == 0) return Attempt.Failed("verification found no records after write")
        // a structurally incomplete file (a png truncated before its terminal IEND) can still
        // carry every record: refuse it so the pristine backup is never deleted for a broken
        // file, the same bar recovery's writeCompleted applies (finding F2)
        if (!scan.structurallyComplete) {
            return Attempt.Failed("verification found a structurally incomplete file after write")
        }
        // a count alone would let a stale record vouch for a dropped write: the exact
        // expected ids must be present, the same bar recovery's writeCompleted applies
        if (!scan.presentIds.containsAll(expected)) {
            return Attempt.Failed("verification missing expected records after write")
        }
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
                    originalName = item.displayName.ifEmpty { item.relativePath },
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
            cacheRow?.let { db.recordCache().upsertSuperset(it) }
            if (text.isBlank()) db.search().delete(item.mediaId) else db.search().upsert(MemoryFts(item.mediaId, text))
        }
    }

    // the ids this write must land, typed records plus carried opaque frames; opaque ids
    // come from the frozen envelope offsets (8..24), no decode needed
    private fun expectedIdHexes(
        records: List<EngramRecord>,
        carryFrames: List<ByteArray>,
    ): Set<String> = (records.map { it.idHex } + carryFrames.map { it.copyOfRange(8, 24).toHex() }).toSet()

    /**
     * Startup safety net: for each lingering backup, restore the original unless
     * the target actually carries every record the write meant to add or never
     * diverged from the backup at all ([WriteJournal.resolve]). A parseable
     * container that lost its records is treated as an interrupted write and
     * rolled back, so the only pristine copy is never dropped on a crash
     * mid-write (finding A, review F3). Returns the target URIs whose restore
     * could not open the file and needs the user's write consent (finding C2);
     * calling this again after consent is granted completes the restore.
     */
    suspend fun recoverPending(): List<String> =
        withContext(io) {
            mutex.withLock {
                journal.pendingBackups().mapNotNull { backup ->
                    (journal.resolve(backup) as? WriteJournal.Resolution.NeedsConsent)?.uri
                }
            }
        }
}
