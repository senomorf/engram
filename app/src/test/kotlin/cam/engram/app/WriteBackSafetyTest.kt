package cam.engram.app

import androidx.test.core.app.ApplicationProvider
import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.scan.RecordScanner
import cam.engram.app.writeback.Annotation
import cam.engram.app.writeback.MediaWriteBack
import cam.engram.app.writeback.StripRepair
import cam.engram.app.writeback.WriteOutcome
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
class WriteBackSafetyTest {
    private val db = EngramDb.inMemory(ApplicationProvider.getApplicationContext())
    private val access = FakeContentAccess()
    private val backupDir =
        File.createTempFile("wbsafety", "").let {
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
    fun tearDown() = db.close()

    private suspend fun seed(
        id: Long,
        bytes: ByteArray,
        takenAt: Long = id,
    ): MediaItemEntity {
        val uri = "content://media/$id"
        access.files[uri] = bytes
        val item =
            MediaItemEntity(id, uri, false, "image/jpeg", "DCIM/Camera/", takenAt, bytes.size.toLong(), id, 0, 0, 0)
        db.media().upsert(listOf(item))
        return item
    }

    @Test
    fun recoveryKeepsAGoodWriteAndDoesNotRollBack() =
        runBlocking {
            val item = seed(1, SyntheticMedia.jpegPlain())
            assertIs<WriteOutcome.Success>(writeBack.write(item, Annotation("kept", null)))
            val written = access.files[item.uri]!!.copyOf()
            assertEquals(1, RecordStream.scan(written).count { it.decoded.crcOk })

            // simulate a crash after verify but before cleanup: a stale backup lingers
            File(backupDir, "1.bak").writeBytes(SyntheticMedia.jpegPlain())
            File(backupDir, "1.meta").writeText("${item.uri}\nfalse\nimage/jpeg")

            writeBack.recoverPending()
            // the annotated file parses, so recovery must not roll it back
            assertContentEquals(written, access.files[item.uri])
            assertTrue(backupDir.listFiles()!!.isEmpty(), "stale backup should be cleared")
        }

    @Test
    fun recoveryRestoresACorruptTarget() =
        runBlocking {
            val original = SyntheticMedia.jpegPlain()
            seed(2, original)
            // half-written garbage at the target, backup holds the pristine original
            access.files["content://media/2"] = ByteArray(3) { 0x11 }
            File(backupDir, "2.bak").writeBytes(original)
            File(backupDir, "2.meta").writeText("content://media/2\nfalse\nimage/jpeg")

            writeBack.recoverPending()
            assertContentEquals(original, access.files["content://media/2"])
        }

    @Test
    fun restoreFailureKeepsTheBackup() =
        runBlocking {
            val item = seed(3, SyntheticMedia.jpegPlain())
            access.corruptWrites = true // verify will fail
            access.rejectRestore = true // and the restore cannot complete
            val outcome = writeBack.write(item, Annotation("x", null))
            assertIs<WriteOutcome.Failed>(outcome)
            assertTrue(outcome.reason.contains("backup"), outcome.reason)
            assertTrue(File(backupDir, "3.bak").exists(), "the only pristine copy must be kept")
        }

    @Test
    fun repairRefusesWhenCacheIdentityDiffers() =
        runBlocking {
            val item = seed(4, SyntheticMedia.jpegPlain(), takenAt = 100)
            assertIs<WriteOutcome.Success>(writeBack.write(item, Annotation("mine", null)))

            // the MediaStore id is now reused for a different capture (different time)
            val reused = db.media().byId(4)!!.copy(takenAtMillis = 999, recordCount = 0)
            db.media().upsert(listOf(reused))
            access.files[item.uri] = SyntheticMedia.jpegPlain() // stripped/other photo

            val repair = StripRepair(db, writeBack)
            assertTrue(repair.strippedItems().none { it.mediaId == 4L }, "identity mismatch must not list as stripped")
            assertTrue(repair.repair(reused) is WriteOutcome.Failed)
        }
}
