package cam.engram.app

import androidx.test.core.app.ApplicationProvider
import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.RecordCacheEntity
import cam.engram.app.data.db.upsertSuperset
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.records.RecordStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * The record cache is keyed by capture, not by device-local media id: a reused
 * MediaStore id must never overwrite the previous capture's only cached memories
 * (D3, D29). Legacy pre-identity rows (identityTakenAt 0) upgrade in place.
 */
@RunWith(RobolectricTestRunner::class)
class RecordCacheSupersetTest {
    private val db = EngramDb.inMemory(ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() = db.close()

    private fun row(
        mediaId: Long,
        identity: Long,
        note: String,
        hash: String = "",
        name: String = "",
    ) = RecordCacheEntity(
        mediaId = mediaId,
        identityTakenAt = identity,
        sizeBytesAtScan = 10,
        recordsBlob = EngramRecord(RecordKind.Note, identity, note.encodeToByteArray()).encode(),
        recordCount = 1,
        updatedMillis = identity,
        originalName = name,
        contentHash = hash,
    )

    private fun noteText(blob: ByteArray): List<String> =
        RecordStream
            .decodeSequence(blob)
            .mapNotNull { it.decoded.record }
            .map { it.payload.decodeToString() }

    @Test
    fun identityMismatchPreservesBothCaptures() =
        runBlocking {
            db.recordCache().upsert(row(5, 100, "old capture", hash = "aa", name = "old.jpg"))
            db.recordCache().upsertSuperset(row(5, 999, "new capture", hash = "bb", name = "new.jpg"))
            val rows = db.recordCache().all().sortedBy { it.identityTakenAt }
            assertEquals(2, rows.size, "a reused media id must never destroy the previous capture's cache")
            assertEquals(listOf(100L, 999L), rows.map { it.identityTakenAt })
            assertEquals(listOf("old capture"), noteText(rows[0].recordsBlob))
            assertEquals("aa", rows[0].contentHash)
            assertEquals("old.jpg", rows[0].originalName)
            assertEquals(listOf("new capture"), noteText(rows[1].recordsBlob))
        }

    @Test
    fun legacyZeroIdentityRowUpgradesInPlace() =
        runBlocking {
            db.recordCache().upsert(row(6, 0, "legacy", hash = "cc"))
            db.recordCache().upsertSuperset(row(6, 777, "fresh"))
            val rows = db.recordCache().all()
            assertEquals(1, rows.size, "the legacy row upgrades, it does not fork")
            assertEquals(777, rows.single().identityTakenAt)
            assertEquals(2, rows.single().recordCount)
            assertEquals("cc", rows.single().contentHash, "an empty fresh hash keeps the stored one")
        }

    @Test
    fun exactIdentityMergeSupersets() =
        runBlocking {
            db.recordCache().upsert(row(7, 50, "first"))
            db.recordCache().upsertSuperset(row(7, 50, "second"))
            val rows = db.recordCache().all()
            assertEquals(1, rows.size)
            assertEquals(2, rows.single().recordCount)
            assertEquals(listOf("second", "first"), noteText(rows.single().recordsBlob))
        }
}
