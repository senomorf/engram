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
import cam.engram.format.mp4.Mp4Caption
import cam.engram.format.records.RecordStream
import cam.engram.format.testing.SyntheticMedia
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
}
