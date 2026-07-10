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

            val repair = StripRepair(db, writeBack, RecordScanner(access))
            assertTrue(repair.strippedItems().none { it.mediaId == 4L }, "identity mismatch must not list as stripped")
            assertTrue(repair.repair(reused) is WriteOutcome.Failed)
        }

    @Test
    fun recoveryRestoresAParseableButRecordlessTarget() =
        runBlocking {
            // a pristine original that already carries a memory becomes the lingering backup
            val donor = seed(10, SyntheticMedia.jpegPlain())
            assertIs<WriteOutcome.Success>(writeBack.write(donor, Annotation("memory", null)))
            val pristine = access.files[donor.uri]!!.copyOf()
            val ids = RecordStream.scan(pristine).filter { it.decoded.crcOk }.mapNotNull { it.decoded.record?.idHex }
            assertTrue(ids.isNotEmpty())

            // a crash truncated the records tail: the target still parses as a JPEG but lost the memory
            val uri = "content://media/11"
            access.files[uri] = SyntheticMedia.jpegPlain()
            File(backupDir, "11.bak").writeBytes(pristine)
            File(backupDir, "11.meta").writeText("$uri\nfalse\nimage/jpeg\n${ids.joinToString(",")}")

            writeBack.recoverPending()

            // the target is missing the expected records, so recovery must restore the backup
            assertContentEquals(pristine, access.files[uri])
        }

    @Test
    fun recoveryKeepsATargetThatHasTheExpectedIds() =
        runBlocking {
            val donor = seed(12, SyntheticMedia.jpegPlain())
            assertIs<WriteOutcome.Success>(writeBack.write(donor, Annotation("memory", null)))
            val complete = access.files[donor.uri]!!.copyOf()
            val ids = RecordStream.scan(complete).filter { it.decoded.crcOk }.mapNotNull { it.decoded.record?.idHex }

            // a crash after the write finished but before cleanup: the target already has the records
            val uri = "content://media/13"
            access.files[uri] = complete
            File(backupDir, "13.bak").writeBytes(SyntheticMedia.jpegPlain()) // the pre-write original
            File(backupDir, "13.meta").writeText("$uri\nfalse\nimage/jpeg\n${ids.joinToString(",")}")

            writeBack.recoverPending()

            // the target carries the expected ids, so recovery keeps it and clears the backup
            assertContentEquals(complete, access.files[uri])
            assertTrue(!File(backupDir, "13.bak").exists(), "completed write should clear the backup")
        }

    // finding 2, mechanism 2: a write that opened and truncated the target then failed must
    // restore from backup, not be misread as "target untouched" and drop the pristine copy
    @Test
    fun truncateThenFailWriteRestoresFromBackup() =
        runBlocking {
            val original = SyntheticMedia.jpegPlain()
            val item = seed(20, original)
            access.failWriteAfterTruncate = true
            assertIs<WriteOutcome.Failed>(writeBack.write(item, Annotation("note", null)))
            assertContentEquals(original, access.files[item.uri])
            assertTrue(backupDir.listFiles()!!.isEmpty(), "backup cleared after a successful restore")
        }

    @Test
    fun truncateThenFailKeepsBackupWhenRestoreAlsoFails() =
        runBlocking {
            val item = seed(21, SyntheticMedia.jpegPlain())
            access.failWriteAfterTruncate = true
            access.rejectRestore = true
            val outcome = writeBack.write(item, Annotation("note", null))
            assertTrue(assertIs<WriteOutcome.Failed>(outcome).reason.contains("backup"), outcome.reason)
            assertTrue(File(backupDir, "21.bak").exists(), "an uncertain write must never delete the backup")
        }

    // finding 2, mechanism 1: a partial backup copy must never be published under the .bak
    // name, so recovery cannot later restore a truncated backup over an intact original
    @Test
    fun partialBackupIsNeverPublished() =
        runBlocking {
            val original = SyntheticMedia.jpegPlain()
            val item = seed(22, original)
            access.partialCopyToFile = true
            assertEquals(
                "cannot back up original",
                assertIs<WriteOutcome.Failed>(writeBack.write(item, Annotation("note", null))).reason,
            )
            assertTrue(backupDir.listFiles()!!.none { it.name == "22.bak" }, "partial backup must not be published")
            assertContentEquals(original, access.files[item.uri])
            writeBack.recoverPending()
            assertContentEquals(original, access.files[item.uri])
        }

    // finding 2, mechanism 3: a same-session retry must rebuild from the committed backup,
    // never re-copy the now-corrupt target over the last pristine copy
    @Test
    fun sameSessionRetryReusesPristineBackup() =
        runBlocking {
            val item = seed(23, SyntheticMedia.jpegPlain())
            access.failWriteAfterTruncate = true
            access.rejectRestore = true
            assertIs<WriteOutcome.Failed>(writeBack.write(item, Annotation("note", null)))
            assertTrue(File(backupDir, "23.bak").exists())
            val copiesAfterFirst = access.copyToFileCount

            access.failWriteAfterTruncate = false
            access.rejectRestore = false
            assertIs<WriteOutcome.Success>(writeBack.write(item, Annotation("note", null)))
            assertEquals(copiesAfterFirst, access.copyToFileCount, "retry must reuse the backup, not re-copy")
            assertEquals(1, RecordStream.scan(access.files[item.uri]!!).count { it.decoded.crcOk })
            assertTrue(backupDir.listFiles()!!.isEmpty(), "backup cleared after the successful retry")
        }
}
