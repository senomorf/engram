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
        mime: String = "image/jpeg",
    ): MediaItemEntity {
        val uri = "content://media/$id"
        access.files[uri] = bytes
        val item =
            MediaItemEntity(id, uri, false, mime, "DCIM/Camera/", takenAt, bytes.size.toLong(), id, 0, 0, 0)
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

    // reviewer D: a save that committed the write but died before the draft delete is retried with
    // the same annotation. Content-addressed record ids let write() recognize the records already
    // landed and skip the append, so the append-only file never gains duplicate note/audio records.
    @Test
    fun repeatedWriteOfSameAnnotationIsIdempotent() =
        runBlocking {
            val item = seed(20, SyntheticMedia.jpegPlain())
            val annotation = Annotation("remember this", null)
            assertIs<WriteOutcome.Success>(writeBack.write(item, annotation))
            val afterFirst = RecordStream.scan(access.files[item.uri]!!).count { it.decoded.crcOk }
            assertEquals(1, afterFirst)

            assertIs<WriteOutcome.Success>(writeBack.write(item, annotation))
            val afterSecond = RecordStream.scan(access.files[item.uri]!!).count { it.decoded.crcOk }
            assertEquals(afterFirst, afterSecond, "a repeated identical write must not duplicate records")
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
    fun preparationFailureNeverTouchesTargetOrWedgesTheItem() =
        runBlocking {
            // a motion photo is refused while building the output, before the target is
            // ever opened: no restore may run (restore itself truncates in place) and no
            // journal may linger to wedge the item
            val bytes = SyntheticMedia.jpegWithXmp("camera=1\nGCamera MotionPhoto present")
            val item = seed(11, bytes)
            access.rejectRestore = true // proves the failure path never even attempts a restore
            val outcome = writeBack.write(item, Annotation("refused", null))
            assertIs<WriteOutcome.Failed>(outcome)
            assertTrue(outcome.reason.contains("motion photo"), outcome.reason)
            assertTrue(!outcome.reason.contains("backup"), outcome.reason)
            assertContentEquals(bytes, access.files[item.uri])
            assertTrue(backupDir.listFiles()!!.isEmpty(), "a preparation failure must clean its journal")
            // not wedged: the same item fails for the same honest reason, not "unresolved"
            val retry = writeBack.write(item, Annotation("again", null))
            assertIs<WriteOutcome.Failed>(retry)
            assertTrue(retry.reason.contains("motion photo"), retry.reason)
        }

    @Test
    fun pristineJournalResolvesWithoutWriteGrant() =
        runBlocking {
            // crash residue from before the first write: the backup equals the target byte
            // for byte. Settling it must not need a write grant (digest compare, no restore).
            val bytes = SyntheticMedia.jpegPlain()
            val item = seed(13, bytes)
            File(backupDir, "13.bak").writeBytes(bytes)
            File(backupDir, "13.meta")
                .writeText("${item.uri}\nfalse\nimage/jpeg\nffffffffffffffffffffffffffffffff")
            access.rejectRestore = true
            val outcome = writeBack.write(item, Annotation("proceeds", null))
            assertIs<WriteOutcome.Success>(outcome)
            assertTrue(RecordStream.scan(access.files[item.uri]!!).any { it.decoded.crcOk })
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

    // finding 2, mechanism 3: a retry after a damaged attempt must never source the write
    // from the corrupt target; resolving the pending journal restores the original first,
    // then the fresh backup is copied from pristine bytes
    @Test
    fun retryAfterFailedRestoreResolvesThenSucceeds() =
        runBlocking {
            val original = SyntheticMedia.jpegPlain()
            val item = seed(23, original)
            access.failWriteAfterTruncate = true
            access.rejectRestore = true
            assertIs<WriteOutcome.Failed>(writeBack.write(item, Annotation("note", null)))
            assertTrue(File(backupDir, "23.bak").exists())
            val copiesAfterFirst = access.copyToFileCount

            access.failWriteAfterTruncate = false
            access.rejectRestore = false
            assertIs<WriteOutcome.Success>(writeBack.write(item, Annotation("note", null)))
            assertEquals(
                copiesAfterFirst + 1,
                access.copyToFileCount,
                "the retry resolves the journal (restoring the original) and backs up the restored bytes",
            )
            assertEquals(1, RecordStream.scan(access.files[item.uri]!!).count { it.decoded.crcOk })
            assertTrue(backupDir.listFiles()!!.isEmpty(), "backup cleared after the successful retry")
        }

    // finding A + C2: a retry whose target cannot even be opened must not delete the only
    // pristine copy, and now surfaces as consent-needed (NotOpened) rather than a dead-end Failed
    @Test
    fun rejectedRetryAfterFailedRestoreKeepsTheOnlyBackup() =
        runBlocking {
            val original = SyntheticMedia.jpegPlain()
            val item = seed(24, original)
            access.failWriteAfterTruncate = true
            access.rejectRestore = true
            assertIs<WriteOutcome.Failed>(writeBack.write(item, Annotation("note", null)))
            assertTrue(File(backupDir, "24.bak").exists())
            val copiesAfterFirst = access.copyToFileCount

            // the target is still damaged and now nothing can be opened at all
            access.failWriteAfterTruncate = false
            access.rejectWrites = true
            val outcome = writeBack.write(item, Annotation("note", null))
            assertIs<WriteOutcome.NotOpened>(outcome)
            assertTrue(File(backupDir, "24.bak").exists(), "the only pristine copy must survive a rejected retry")
            assertEquals(copiesAfterFirst, access.copyToFileCount)

            // once the user grants consent (writes work again), recovery restores the original
            access.rejectWrites = false
            access.rejectRestore = false
            assertTrue(writeBack.recoverPending().isEmpty())
            assertContentEquals(original, access.files[item.uri])
        }

    // finding C2: after process death, restoring a truncated target needs a write grant the
    // background context lacks. That must surface as consent-needed, not a silent dead end.
    @Test
    fun recoveryNeedingConsentSurfacesAsNotOpenedNotFailed() =
        runBlocking {
            val original = SyntheticMedia.jpegPlain()
            val item = seed(30, original)
            // a crash left the target truncated with the pristine original in the backup; the
            // meta's ids are absent from the target, so recovery must restore, but cannot open it
            access.files[item.uri] = ByteArray(3) { 0x11 }
            File(backupDir, "30.bak").writeBytes(original)
            File(backupDir, "30.meta").writeText("${item.uri}\nfalse\nimage/jpeg\ndeadbeef")
            access.rejectRestore = true

            // a fresh save on the same item surfaces consent-needed, not a dead-end Failed
            assertIs<WriteOutcome.NotOpened>(writeBack.write(item, Annotation("note", null)))
            assertTrue(File(backupDir, "30.bak").exists(), "the only pristine copy must survive")

            // background recovery reports the item as needing consent instead of silently dropping it
            assertEquals(listOf(item.uri), writeBack.recoverPending())
            assertTrue(File(backupDir, "30.bak").exists())
        }

    // finding C2: a restore that opens the target but does not complete (OpenedUncertain) is
    // genuinely unresolved, distinct from a NotOpened restore that needs consent: it fails
    // (keeping the backup) and is not reported as needing consent
    @Test
    fun recoveryLeftUncertainStaysUnresolvedAndFails() =
        runBlocking {
            val original = SyntheticMedia.jpegPlain()
            val item = seed(32, original)
            access.files[item.uri] = ByteArray(3) { 0x11 }
            File(backupDir, "32.bak").writeBytes(original)
            File(backupDir, "32.meta").writeText("${item.uri}\nfalse\nimage/jpeg\ndeadbeef")
            access.uncertainRestore = true

            val outcome = writeBack.write(item, Annotation("note", null))
            assertTrue(assertIs<WriteOutcome.Failed>(outcome).reason.contains("unresolved"), outcome.reason)
            assertTrue(File(backupDir, "32.bak").exists(), "an unresolved restore keeps the backup")
            assertTrue(writeBack.recoverPending().isEmpty(), "an uncertain restore is not consent-needed")
        }

    // finding C2: once the user grants the write consent, retrying recovery restores the
    // original and clears the journal
    @Test
    fun recoveryRetryAfterConsentRestoresTheOriginal() =
        runBlocking {
            val original = SyntheticMedia.jpegPlain()
            val item = seed(31, original)
            access.files[item.uri] = ByteArray(3) { 0x11 }
            File(backupDir, "31.bak").writeBytes(original)
            File(backupDir, "31.meta").writeText("${item.uri}\nfalse\nimage/jpeg\ndeadbeef")
            access.rejectRestore = true

            assertEquals(listOf(item.uri), writeBack.recoverPending())
            assertContentEquals(ByteArray(3) { 0x11 }, access.files[item.uri], "still truncated before consent")

            access.rejectRestore = false // the user granted the write consent
            assertTrue(writeBack.recoverPending().isEmpty())
            assertContentEquals(original, access.files[item.uri])
            assertTrue(backupDir.listFiles()!!.isEmpty(), "journal cleared after the restore")
        }

    // finding F1: a reused MediaStore id now points at a different capture; restoring the old
    // backup would overwrite the unrelated new photo and delete the journal, losing it. A positive
    // capture-identity mismatch must orphan the backup instead of writing it over the new photo.
    @Test
    fun recoveryRefusesToOverwriteAReusedTarget() =
        runBlocking {
            val uri = "content://media/40"
            val captureB = SyntheticMedia.jpegWithFillBytes() // a distinct, unrelated photo
            access.files[uri] = captureB
            access.captureIdentity[uri] = 200L // the target now reports capture B's identity
            val backupA = SyntheticMedia.jpegPlain()
            File(backupDir, "40.bak").writeBytes(backupA)
            // 5-line sidecar: A's identity (100) is the anchor; A's expected id is absent from B
            File(backupDir, "40.meta").writeText("$uri\nfalse\nimage/jpeg\ndeadbeef\n100")

            assertTrue(writeBack.recoverPending().isEmpty(), "a reused target is not consent-needed")

            assertContentEquals(captureB, access.files[uri], "the unrelated reused photo must not be overwritten")
            assertTrue(!File(backupDir, "40.bak").exists(), "the backup is renamed out of the recovery scan")
            assertTrue(File(backupDir, "40.bak.orphan").exists(), "A's backup is preserved as an orphan, not deleted")
            assertTrue(!File(backupDir, "40.meta").exists(), "the journal is cleared so resolve stops retrying")
        }

    // finding F1: the same id still points at capture A (a partial write truncated it), so the
    // capture identity is unchanged and recovery must restore the backup exactly as before
    @Test
    fun recoveryRestoresWhenIdentityStillMatches() =
        runBlocking {
            val uri = "content://media/41"
            access.files[uri] = ByteArray(3) { 0x11 } // A, truncated by a partial write
            access.captureIdentity[uri] = 100L // still A's identity
            val original = SyntheticMedia.jpegPlain()
            File(backupDir, "41.bak").writeBytes(original)
            File(backupDir, "41.meta").writeText("$uri\nfalse\nimage/jpeg\ndeadbeef\n100")

            writeBack.recoverPending()

            assertContentEquals(original, access.files[uri], "a matching identity restores the original")
            assertTrue(backupDir.listFiles()!!.isEmpty(), "journal cleared after the restore")
        }

    // finding F2: a png write that lands but is cut off before its terminal IEND is structurally
    // incomplete even though every record chunk is present; verify must reject it and roll back to
    // the pristine original rather than "succeed" and delete the backup for a broken file
    @Test
    fun verifyRejectsAStructurallyTruncatedPngAndRollsBack() =
        runBlocking {
            val item = seed(50, SyntheticMedia.png1x1(), mime = "image/png")
            access.truncateWrites = true // the png write lands but loses its terminal IEND
            val outcome = writeBack.write(item, Annotation("note", null))
            assertTrue(assertIs<WriteOutcome.Failed>(outcome).reason.contains("structurally"), outcome.reason)
            assertContentEquals(SyntheticMedia.png1x1(), access.files[item.uri], "the truncated write is rolled back")
            assertTrue(backupDir.listFiles()!!.isEmpty(), "backup cleared only after the restore")
        }

    // finding F2: recovery must not settle (and delete the backup for) a structurally truncated png
    // just because it carries every expected record id; it must restore the pristine original
    @Test
    fun recoveryRestoresAStructurallyTruncatedPng() =
        runBlocking {
            val donor = seed(51, SyntheticMedia.png1x1(), mime = "image/png")
            assertIs<WriteOutcome.Success>(writeBack.write(donor, Annotation("memory", null)))
            val complete = access.files[donor.uri]!!.copyOf() // a valid embedded png (records + IEND)
            val ids = RecordScanner(access).presentIds(donor.uri, false, "image/png")

            // a crash truncated the png before its terminal IEND: every record is present but the
            // file is structurally incomplete; the backup holds the pristine pre-write original
            val uri = "content://media/52"
            access.files[uri] = complete.copyOfRange(0, complete.size - 12)
            File(backupDir, "52.bak").writeBytes(SyntheticMedia.png1x1())
            File(backupDir, "52.meta").writeText("$uri\nfalse\nimage/png\n${ids.joinToString(",")}")

            writeBack.recoverPending()

            assertContentEquals(SyntheticMedia.png1x1(), access.files[uri], "a truncated png restores the original")
            assertTrue(!File(backupDir, "52.bak").exists(), "the backup is cleared only after the restore")
        }

    // finding B: verification must check the exact new records landed, not just that some
    // record parses; a stale record from an earlier save must not vouch for a write the
    // provider silently dropped
    @Test
    fun verifyRejectsAWriteThatLostTheNewRecords() =
        runBlocking {
            val item = seed(26, SyntheticMedia.jpegPlain())
            assertIs<WriteOutcome.Success>(writeBack.write(item, Annotation("first", null)))
            val afterFirst = access.files[item.uri]!!.copyOf()

            access.ignoreWrites = true
            val outcome = writeBack.write(item, Annotation("second", null))
            assertTrue(assertIs<WriteOutcome.Failed>(outcome).reason.contains("verification"), outcome.reason)
            assertContentEquals(afterFirst, access.files[item.uri], "the target keeps the first save's bytes")
            assertTrue(backupDir.listFiles()!!.isEmpty(), "backup cleared after the successful restore")
            assertEquals(1, db.media().byId(26)!!.recordCount)
        }

    // finding A, second path: a crash between verify and cleanup leaves a stale pre-write
    // backup; a retry must not embed from it and silently drop the completed write's records
    @Test
    fun retryAfterCompletedButUncleanedWriteKeepsPriorRecords() =
        runBlocking {
            val pristine = SyntheticMedia.jpegPlain()
            val item = seed(25, pristine)
            assertIs<WriteOutcome.Success>(writeBack.write(item, Annotation("first", null)))
            val afterFirst = access.files[item.uri]!!.copyOf()
            val firstIds =
                RecordStream
                    .scan(afterFirst)
                    .filter { it.decoded.crcOk }
                    .mapNotNull { it.decoded.record?.idHex }

            // crash after verify but before cleanup: the pre-write original lingers as backup
            File(backupDir, "25.bak").writeBytes(pristine)
            File(backupDir, "25.meta").writeText("${item.uri}\nfalse\nimage/jpeg\n${firstIds.joinToString(",")}")

            assertIs<WriteOutcome.Success>(writeBack.write(item, Annotation("second", null)))
            val ids =
                RecordStream
                    .scan(access.files[item.uri]!!)
                    .filter { it.decoded.crcOk }
                    .mapNotNull { it.decoded.record?.idHex }
            assertEquals(2, ids.size, "the completed first write must survive the retry")
            assertTrue(ids.containsAll(firstIds))
            assertTrue(backupDir.listFiles()!!.isEmpty())
        }
}
