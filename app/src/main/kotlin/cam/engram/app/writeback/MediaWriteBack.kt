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
import cam.engram.format.Digests
import cam.engram.format.archive.EngramArchive
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
import java.io.FileInputStream

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
        val records = recordFactory.fromAnnotation(annotation, item.mediaId).toMutableList()
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
                // the identity the journal anchors on (and the record-cache key) must match the
                // live provider, not just a possibly-stale index row (review N2): if the
                // MediaStore id was reused since the last reconcile, writing would truncate the
                // unrelated new photo, and the resulting journal, anchored on the old identity,
                // would orphan its own backup instead of restoring it. A null read is a query
                // failure or a vanished row, which the write itself surfaces, so it proceeds.
                val live = access.readCaptureIdentity(item.uri)
                if (live != null && live != item.takenAtMillis) {
                    return@withLock WriteOutcome.Failed("photo changed since the last sync; it will re-sync shortly")
                }
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
                // idempotency (reviewer D): a save that committed the write but died before its draft
                // was deleted is retried with the same annotation. Content-addressed ids let us spot
                // records already in the target; when the whole annotation is present in a sound file,
                // skip the append (reconcile the db, consume the draft), else append only the records
                // still missing so a partial retry cannot duplicate the ones already there.
                val preScan = scanner.scan(item.uri, item.isVideo, item.mime)
                val present = preScan?.presentIds.orEmpty()
                if (preScan != null && preScan.structurallyComplete && present.containsAll(expectedIds)) {
                    val outcome = successOf(preScan)
                    finishSuccess(item, outcome, preScan)
                    return@withLock outcome
                }
                val recordsToWrite = records.filterNot { it.idHex in present }
                val framesToWrite = carryFrames.filterNot { it.copyOfRange(8, 24).toHex() in present }
                if (!journal.writeSidecar(item, expectedIds)) {
                    // a backup without its sidecar would be unrecoverable residue (review N1 minor)
                    return@withLock WriteOutcome.Failed("cannot record the write journal")
                }
                if (!journal.publishBackup(item)) {
                    return@withLock WriteOutcome.Failed("cannot back up original")
                }
                // preparation only reads (the backup, temp files): a failure here leaves the
                // target untouched by construction, so the journal is discarded, never restored
                // (restore itself opens the target with truncation and must not run needlessly)
                val prepared =
                    runCatching { prepare(item, backup, recordsToWrite, mirrorText, framesToWrite) }
                        .getOrElse { e ->
                            journal.cleanup(item.mediaId)
                            return@withLock WriteOutcome.Failed(e.message ?: "write preparation failed")
                        }
                // the exact bytes this write means to land; recorded in the journal before the
                // target is opened so recovery holds a crashed write to the same bar (issue #97)
                val expected = digestOf(prepared)
                journal.writeSidecar(item, expectedIds, expected)
                val attempt =
                    runCatching { commit(item, prepared, expectedIds, expected) }
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

    // the digest and size of the fully prepared output, computed before the target is opened
    private fun digestOf(prepared: Prepared): WriteJournal.PreparedOutput =
        when (prepared) {
            is Prepared.Photo ->
                WriteJournal.PreparedOutput(
                    EngramArchive.contentHashName(prepared.bytes),
                    prepared.bytes.size.toLong(),
                )
            // stream the temp file: a large video must not be loaded whole to hash it
            is Prepared.Video ->
                WriteJournal.PreparedOutput(
                    FileInputStream(prepared.temp).channel.use { Digests.sha256Hex(it) },
                    prepared.temp.length(),
                )
        }

    private fun commit(
        item: MediaItemEntity,
        prepared: Prepared,
        expectedIds: Set<String>,
        expected: WriteJournal.PreparedOutput,
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
            WriteResult.Ok -> verify(item, expectedIds, expected)
        }
    }

    // one scan serves both the success verdict and the index rows, so what was
    // verified is exactly what gets persisted (a second scan could silently diverge)
    private fun verify(
        item: MediaItemEntity,
        expected: Set<String>,
        preparedOutput: WriteJournal.PreparedOutput,
    ): Attempt {
        val scan =
            scanner.scan(item.uri, item.isVideo, item.mime)
                ?: return Attempt.Failed("verification could not read file back")
        if (scan.recordCount == 0) return Attempt.Failed("verification found no records after write")
        // the strongest bar: the target must be byte-for-byte the output we prepared. Record
        // checks alone cannot see a provider that wrote a transformed image while preserving
        // the appended records, and structural completeness is only a real check for png
        // (issue #97). The scan already hashed the read-back bytes, so this costs nothing.
        val size = access.withChannel(item.uri) { it.size() }
        if (size != preparedOutput.sizeBytes || scan.contentHash != preparedOutput.digestHex) {
            return Attempt.Failed("verification found different bytes than the write prepared")
        }
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
        return Attempt.Verified(successOf(scan), scan)
    }

    // the success verdict for a scan: records landed, payload size, soft-cap flag
    private fun successOf(scan: ScanOutcome): WriteOutcome.Success =
        WriteOutcome.Success(
            recordCount = scan.recordCount,
            payloadLength = scan.payloadLength,
            overSoftCap = scan.payloadLength > SOFT_CAP_BYTES,
        )

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
                sweepStaleTemps()
                journal.pendingBackups().mapNotNull { backup ->
                    // one corrupt journal must not abort the pass and starve every journal
                    // after it (review N6): a throwing resolve counts as unresolved and is
                    // retried on a later pass
                    val resolution =
                        runCatching { journal.resolve(backup) }
                            .getOrDefault(WriteJournal.Resolution.Unresolved)
                    (resolution as? WriteJournal.Resolution.NeedsConsent)?.uri
                }
            }
        }

    // a crashed prepare strands its video temp; any *.new.mp4 here is residue, because the
    // live one only exists inside a writeRecords call this mutex excludes (review N6 minor)
    private fun sweepStaleTemps() {
        backupDir.listFiles { f -> f.name.endsWith(".new.mp4") }?.forEach { it.delete() }
    }

    /**
     * Backups shelved because their media id was reused for a different capture (finding F1):
     * the displaced photo's only remaining copy, kept out of the recovery scan so it is never
     * written over the new photo. They are invisible and unbounded until surfaced (issue #92),
     * so the Tools screen lists them and offers to save or discard.
     */
    suspend fun shelvedBackups(): List<ShelvedBackup> =
        withContext(io) {
            mutex.withLock {
                journal.orphanBackups().map { ShelvedBackup(it.name, it.length()) }.sortedBy { it.name }
            }
        }

    /** Streams every shelved backup into [sink]; returns how many landed. */
    suspend fun copyShelvedBackups(sink: ShelvedSink): Int =
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
    suspend fun discardShelvedBackups(): Int =
        withContext(io) {
            mutex.withLock { journal.orphanBackups().count { it.delete() } }
        }
}
