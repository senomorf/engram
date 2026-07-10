package cam.engram.app

import androidx.test.core.app.ApplicationProvider
import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.EnrichmentCacheEntity
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.media.MediaSource
import cam.engram.app.data.media.SourceItem
import cam.engram.app.data.scan.RecordScanner
import cam.engram.app.domain.Reconciler
import cam.engram.app.writeback.Annotation
import cam.engram.app.writeback.MediaWriteBack
import cam.engram.app.writeback.WriteOutcome
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.EnrichmentPayload
import cam.engram.format.records.RecordKind
import cam.engram.format.records.RecordStream
import cam.engram.format.testing.SyntheticMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class EnrichmentFlowTest {
    private val db = EngramDb.inMemory(ApplicationProvider.getApplicationContext())
    private val access = FakeContentAccess()
    private val backupDir =
        File.createTempFile("enrich", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @After
    fun tearDown() = db.close()

    private fun enrichmentRecord(place: String): EngramRecord =
        EngramRecord(
            RecordKind.Enrichment,
            1,
            EnrichmentPayload(linkedMapOf(EnrichmentPayload.KEY_PLACE to place)).encode(),
            ByteArray(EngramRecord.ID_LENGTH) { 9 },
            "engram-android",
        )

    private suspend fun seedItem(id: Long): MediaItemEntity {
        val uri = "content://media/$id"
        access.files[uri] = SyntheticMedia.jpegPlain()
        val item =
            MediaItemEntity(
                id,
                uri,
                false,
                "image/jpeg",
                "DCIM/Camera/",
                id,
                access.files[uri]!!.size.toLong(),
                id,
                0,
                0,
                0,
            )
        db.media().upsert(listOf(item))
        return item
    }

    @Test
    fun savePathIncludesCachedEnrichmentWithoutNetwork() =
        runBlocking {
            val item = seedItem(1)
            db.enrichmentCache().upsert(EnrichmentCacheEntity(1, enrichmentRecord("Batumi").encode(), 1))
            var networkCalls = 0
            val writeBack =
                MediaWriteBack(
                    db = db,
                    access = access,
                    scanner = RecordScanner(access),
                    backupDir = backupDir,
                    io = Dispatchers.Unconfined,
                    cachedEnrichment = { id ->
                        db.enrichmentCache().byId(id.mediaId)?.let { EngramRecord.decodeAt(it.recordBlob, 0)?.record }
                    },
                )
            // the write path must not touch any network provider
            val outcome = writeBack.write(item, Annotation("sunset", null))
            assertIs<WriteOutcome.Success>(outcome)
            assertEquals(0, networkCalls)
            val records = RecordStream.scan(access.files[item.uri]!!).mapNotNull { it.decoded.record }
            assertEquals(2, records.size)
            assertTrue(records.any { it.kind == RecordKind.Enrichment }, "enrichment must ride the user write")
        }

    @Test
    fun reconcilePrefetchesEnrichmentIntoCache() =
        runBlocking {
            seedItem(2)
            val source =
                object : MediaSource {
                    override suspend fun snapshot(includeScreenshots: Boolean): List<SourceItem> =
                        listOf(
                            SourceItem(2, "content://media/2", false, "image/jpeg", "DCIM/Camera/", 2, 10, 2),
                        )
                }
            var fetches = 0
            val reconciler =
                Reconciler(
                    db = db,
                    source = source,
                    scanner = RecordScanner(access),
                    access = access,
                    includeScreenshots = { true },
                    io = Dispatchers.Unconfined,
                    enrichmentPrefetch = {
                        fetches++
                        enrichmentRecord("Tbilisi").encode()
                    },
                    clock = { 1L },
                )
            reconciler.reconcile()
            assertEquals(1, fetches)
            val cached = db.enrichmentCache().byId(2)
            assertTrue(cached != null, "prefetch should populate enrichment_cache")
            // a second reconcile must not re-fetch an already-cached item
            reconciler.reconcile()
            assertEquals(1, fetches)
        }
}
