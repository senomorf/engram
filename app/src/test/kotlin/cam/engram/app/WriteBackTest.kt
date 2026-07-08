package cam.engram.app

import androidx.test.core.app.ApplicationProvider
import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.scan.RecordScanner
import cam.engram.app.writeback.Annotation
import cam.engram.app.writeback.MediaWriteBack
import cam.engram.app.writeback.StripRepair
import cam.engram.app.writeback.WriteOutcome
import cam.engram.format.mp4.Mp4Caption
import cam.engram.format.records.RecordStream
import cam.engram.format.testing.SyntheticMedia
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
}
