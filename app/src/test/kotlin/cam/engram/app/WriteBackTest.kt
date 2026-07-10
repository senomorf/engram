package cam.engram.app

import androidx.test.core.app.ApplicationProvider
import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.db.RecordCacheEntity
import cam.engram.app.data.scan.RecordScanner
import cam.engram.app.writeback.Annotation
import cam.engram.app.writeback.MediaWriteBack
import cam.engram.app.writeback.StripRepair
import cam.engram.app.writeback.WriteOutcome
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.mp4.Mp4Caption
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.records.RecordStream
import cam.engram.format.testing.SyntheticMedia
import cam.engram.format.xmp.XmpCoreEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WriteBackTest {
    private val db = EngramDb.inMemory(ApplicationProvider.getApplicationContext())
    private val access = FakeContentAccess()
    private val backupDir =
        File.createTempFile("writeback", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    private val writeBack =
        MediaWriteBack(
            db = db,
            access = access,
            scanner = RecordScanner(access),
            backupDir = backupDir,
            io = Dispatchers.Unconfined,
        )

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed(
        id: Long,
        bytes: ByteArray,
        isVideo: Boolean = false,
        mime: String = "image/jpeg",
    ): MediaItemEntity {
        val uri = "content://media/$id"
        access.files[uri] = bytes
        val item =
            MediaItemEntity(
                mediaId = id,
                uri = uri,
                isVideo = isVideo,
                mime = mime,
                relativePath = "DCIM/Camera/",
                takenAtMillis = id,
                sizeBytes = bytes.size.toLong(),
                dateModified = id,
                recordCount = 0,
                payloadLength = 0,
                lastScanMillis = 0,
            )
        db.media().upsert(listOf(item))
        return item
    }

    @Test
    fun photoWriteSucceedsAndIndexes() =
        runBlocking {
            val item = seed(1, SyntheticMedia.jpegWithMpfSecondary())
            val audio = File.createTempFile("note", ".ogg").apply { writeBytes(ByteArray(200) { 2 }) }
            val outcome = writeBack.write(item, Annotation("закат", audio))
            val success = assertIs<WriteOutcome.Success>(outcome)
            assertEquals(2, success.recordCount)
            assertEquals(2, RecordStream.scan(access.files[item.uri]!!).count { it.decoded.crcOk })
            assertEquals(2, db.media().byId(1)!!.recordCount)
            assertEquals(2, db.recordCache().byId(1)!!.recordCount)
            assertTrue(backupDir.listFiles()!!.isEmpty(), "backup must be cleaned after success")
        }

    @Test
    fun userWriteNeverShrinksTheRecoveryCache() =
        runBlocking {
            val a = EngramRecord(RecordKind.Note, 1, "a".encodeToByteArray(), ByteArray(16) { 1 })
            val b = EngramRecord(RecordKind.Note, 2, "b".encodeToByteArray(), ByteArray(16) { 2 })
            // the live file carries only A; the cache still holds A+B from before a partial strip
            val item = seed(8, JpegEmbedder(XmpCoreEngine()).embed(SyntheticMedia.jpegPlain(), listOf(a), "a"))
            db.recordCache().upsert(
                RecordCacheEntity(
                    mediaId = 8,
                    identityTakenAt = item.takenAtMillis,
                    sizeBytesAtScan = item.sizeBytes,
                    recordsBlob = RecordStream.encode(listOf(a, b)),
                    recordCount = 2,
                    updatedMillis = 0,
                ),
            )
            writeBack.write(item, Annotation("c", null))
            val cache = db.recordCache().byId(8)!!
            assertEquals(3, cache.recordCount, "a new save must not shrink the recovery cache")
            val ids = RecordStream.decodeSequence(cache.recordsBlob).mapNotNull { it.decoded.record?.idHex }
            assertTrue(b.idHex in ids, "the cached-only record must survive the save")
        }

    @Test
    fun indexCommitIsAtomicWhenTheCacheInsertFails() =
        runBlocking {
            val item = seed(7, SyntheticMedia.jpegPlain())
            // simulate the record_cache insert dying mid-index: the media row must not
            // commit claiming records the non-rebuildable cache never received (D3)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_cache BEFORE INSERT ON record_cache BEGIN SELECT RAISE(ABORT, 'injected'); END",
            )
            val result = runCatching { writeBack.write(item, Annotation("память", null)) }
            assertTrue(result.isFailure, "the injected cache failure must surface, not be swallowed")
            assertEquals(0, db.media().byId(7)!!.recordCount, "media row must not outlive the failed cache row")
            assertEquals(null, db.recordCache().byId(7))
            assertEquals(1, backupDir.listFiles()!!.count { it.extension == "bak" }, "backup stays for recovery")
        }

    @Test
    fun recoverPendingRestoresVideoWhenTargetNoLongerParses() =
        runBlocking {
            val uri = "content://media/50"
            access.files[uri] = ByteArray(4) { 0x11 } // a broken target that will not parse as mp4
            File(backupDir, "50.bak").writeBytes(SyntheticMedia.mp4Minimal())
            File(backupDir, "50.meta").writeText("$uri\ntrue\nvideo/mp4")
            writeBack.recoverPending()
            // the pristine backup is restored because the target failed to parse
            assertContentEquals(SyntheticMedia.mp4Minimal(), access.files[uri]!!)
        }

    @Test
    fun backupFailureIsReported() =
        runBlocking {
            // a DB row whose bytes are not in the fake resolver: the backup copy cannot be made
            val item =
                MediaItemEntity(
                    mediaId = 99,
                    uri = "content://media/99",
                    isVideo = false,
                    mime = "image/jpeg",
                    relativePath = "DCIM/Camera/",
                    takenAtMillis = 99,
                    sizeBytes = 0,
                    dateModified = 99,
                    recordCount = 0,
                    payloadLength = 0,
                    lastScanMillis = 0,
                )
            db.media().upsert(listOf(item))
            val outcome = writeBack.write(item, Annotation("x", null))
            assertEquals("cannot back up original", assertIs<WriteOutcome.Failed>(outcome).reason)
        }

    @Test
    fun rejectedWriteLeavesFileUntouched() =
        runBlocking {
            val original = SyntheticMedia.jpegPlain()
            val item = seed(2, original)
            access.rejectWrites = true
            val outcome = writeBack.write(item, Annotation("x", null))
            assertEquals("media write rejected", assertIs<WriteOutcome.Failed>(outcome).reason)
            assertContentEquals(original, access.files[item.uri]!!)
        }

    @Test
    fun failedVerificationRestoresOriginal() =
        runBlocking {
            val original = SyntheticMedia.jpegPlain()
            val item = seed(3, original)
            access.corruptWrites = true
            val outcome = writeBack.write(item, Annotation("x", null))
            assertIs<WriteOutcome.Failed>(outcome)
            // restore path must bring the pristine original back, not the garbage
            access.corruptWrites = false
            assertContentEquals(original, access.files[item.uri]!!)
            assertEquals(0, db.media().byId(3)!!.recordCount)
        }

    @Test
    fun videoWriteEmbedsRecordsAndCaption() =
        runBlocking {
            val item = seed(4, SyntheticMedia.mp4MoovLast(), isVideo = true, mime = "video/mp4")
            val outcome = writeBack.write(item, Annotation("clip note", null))
            assertIs<WriteOutcome.Success>(outcome)
            assertEquals("clip note", Mp4Caption.readCaption(access.files[item.uri]!!))
        }

    @Test
    fun stripDetectionAndRepairRestoreSameRecords() =
        runBlocking {
            val item = seed(5, SyntheticMedia.jpegPlain())
            assertIs<WriteOutcome.Success>(writeBack.write(item, Annotation("память", null)))
            val originalId =
                RecordStream
                    .scan(access.files[item.uri]!!)
                    .single()
                    .decoded.record!!
                    .idHex

            // simulate a stripping pipeline replacing the file
            access.files[item.uri] = SyntheticMedia.jpegPlain()
            db.media().upsert(listOf(db.media().byId(5)!!.copy(recordCount = 0)))

            val repair = StripRepair(db, writeBack)
            assertEquals(listOf(5L), repair.strippedItems().map { it.mediaId })
            assertIs<WriteOutcome.Success>(repair.repair(db.media().byId(5)!!))
            val restored =
                RecordStream
                    .scan(access.files[item.uri]!!)
                    .single()
                    .decoded.record!!
            assertEquals(originalId, restored.idHex, "repair must restore history, not invent it")
            assertEquals(0, repair.strippedItems().size)
        }

    @Test
    fun repairPreservesUnknownKindRecords() =
        runBlocking {
            val scanner = RecordScanner(access)
            val item = seed(6, SyntheticMedia.jpegPlain())
            assertIs<WriteOutcome.Success>(writeBack.write(item, Annotation("note", null)))

            // the file now also carries a forward-compat unknown-kind record after ours
            val withUnknown = access.files[item.uri]!! + SyntheticMedia.unknownKindFrame()
            access.files[item.uri] = withUnknown
            assertTrue(
                RecordStream.scan(withUnknown).any { it.decoded.crcOk && it.decoded.record == null },
                "fixture must contain an unknown-kind record",
            )

            // scanning caches the records; then a cloud pipeline strips the file
            val scanned = scanner.scan(item.uri, false, "image/jpeg")!!
            db.recordCache().upsert(
                RecordCacheEntity(
                    6,
                    item.takenAtMillis,
                    withUnknown.size.toLong(),
                    scanned.recordsBlob!!,
                    scanned.recordCount,
                    0,
                ),
            )
            db.media().upsert(listOf(db.media().byId(6)!!.copy(recordCount = 0)))
            access.files[item.uri] = SyntheticMedia.jpegPlain()

            assertIs<WriteOutcome.Success>(StripRepair(db, writeBack).repair(db.media().byId(6)!!))

            // strip-repair is a rewriter: the spec says it must preserve unknown kinds, not drop them
            assertTrue(
                RecordStream.scan(access.files[item.uri]!!).any { it.decoded.crcOk && it.decoded.record == null },
                "unknown-kind record must survive strip-repair",
            )
        }

    @Test
    fun partialStripIsFlaggedNotOnlyTotalStrip() =
        runBlocking {
            val item = seed(7, SyntheticMedia.jpegPlain())
            // the file now carries 1 record but the cache holds 2 for the same capture (finding 4)
            db.media().upsert(listOf(db.media().byId(7)!!.copy(recordCount = 1)))
            db.recordCache().upsert(RecordCacheEntity(7, item.takenAtMillis, 0, ByteArray(0), 2, 0))
            assertEquals(listOf(7L), StripRepair(db, writeBack).strippedItems().map { it.mediaId })
        }
}
