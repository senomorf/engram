package cam.engram.app

import androidx.test.core.app.ApplicationProvider
import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.media.MediaSource
import cam.engram.app.data.media.SourceItem
import cam.engram.app.data.scan.RecordScanner
import cam.engram.app.domain.Reconciler
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ReconcilerTest {
    private val db = EngramDb.inMemory(ApplicationProvider.getApplicationContext())

    private val snapshot = mutableListOf<SourceItem>()

    private val access = FakeContentAccess()
    private val files get() = access.files

    private val source =
        object : MediaSource {
            override suspend fun snapshot(includeScreenshots: Boolean): List<SourceItem> = snapshot.toList()
        }

    private val reconciler =
        Reconciler(
            db = db,
            source = source,
            scanner = RecordScanner(access),
            includeScreenshots = { true },
            io = Dispatchers.Unconfined,
            clock = { 1000L },
        )

    @After
    fun tearDown() {
        db.close()
    }

    private fun addPhoto(
        id: Long,
        bytes: ByteArray,
        mime: String = "image/jpeg",
        isVideo: Boolean = false,
        takenAt: Long = id,
    ) {
        val uri = "content://media/images/$id"
        files[uri] = bytes
        snapshot +=
            SourceItem(
                mediaId = id,
                uri = uri,
                isVideo = isVideo,
                mime = mime,
                relativePath = "DCIM/Camera/",
                takenAtMillis = takenAt,
                sizeBytes = bytes.size.toLong(),
                dateModified = id,
            )
    }

    @Test
    fun addsScansAndCountsItems() =
        runBlocking {
            addPhoto(1, SyntheticMedia.jpegPlain())
            val annotated =
                cam.engram.format.jpeg
                    .JpegEmbedder(FakeXmp())
                    .embed(
                        SyntheticMedia.jpegPlain(),
                        listOf(EngramRecord(RecordKind.Note, 5, "hi".encodeToByteArray())),
                        "hi",
                    )
            addPhoto(2, annotated)

            val stats = reconciler.reconcile()
            assertEquals(2, stats.added)
            assertEquals(2, stats.scanned)

            val items = db.media().all().associateBy { it.mediaId }
            assertEquals(0, items[1L]!!.recordCount)
            assertEquals(1, items[2L]!!.recordCount)
            assertEquals(1, db.recordCache().byId(2L)!!.recordCount)
            // the scanner content-addresses images at scan time so a later orphan can export
            assertTrue(
                db
                    .recordCache()
                    .byId(2L)!!
                    .contentHash
                    .isNotEmpty(),
                "the scanned photo is content-addressed",
            )
        }

    @Test
    fun removalAndSizeChangeRescan() =
        runBlocking {
            addPhoto(1, SyntheticMedia.jpegPlain())
            reconciler.reconcile()

            // file replaced by a bigger one: must rescan
            val bigger = SyntheticMedia.jpegPlain() + ByteArray(10)
            files["content://media/images/1"] = bigger
            snapshot[0] = snapshot[0].copy(sizeBytes = bigger.size.toLong())
            val stats = reconciler.reconcile()
            assertEquals(1, stats.scanned)

            snapshot.clear()
            val stats2 = reconciler.reconcile()
            assertEquals(1, stats2.removed)
            assertEquals(0, db.media().all().size)
        }

    @Test
    fun modifiedTimeChangeRescansEvenWhenSizeIsUnchanged() =
        runBlocking {
            addPhoto(1, SyntheticMedia.jpegPlain())
            reconciler.reconcile()
            assertEquals(0, db.media().byId(1)!!.recordCount)

            // an editor rewrites metadata in place: same byte size, new mtime (review F11)
            snapshot[0] = snapshot[0].copy(dateModified = 999)
            val stats = reconciler.reconcile()
            assertEquals(1, stats.scanned)
        }

    @Test
    fun partialStripKeepsTheCachedSuperset() =
        runBlocking {
            val embedder =
                cam.engram.format.jpeg
                    .JpegEmbedder(FakeXmp())
            val a = EngramRecord(RecordKind.Note, 1, "keep-a".encodeToByteArray())
            val b = EngramRecord(RecordKind.Note, 2, "keep-b".encodeToByteArray())
            // the file starts with both records; reconcile caches {A, B}
            addPhoto(1, embedder.embed(SyntheticMedia.jpegPlain(), listOf(a, b), "keep-b"))
            reconciler.reconcile()
            assertEquals(2, db.recordCache().byId(1)!!.recordCount)

            // a strip pipeline drops B, leaving only A; the file changes so reconcile rescans
            val stripped = embedder.embed(SyntheticMedia.jpegPlain(), listOf(a), "keep-a")
            files["content://media/images/1"] = stripped
            snapshot[0] = snapshot[0].copy(sizeBytes = stripped.size.toLong(), dateModified = 2)
            reconciler.reconcile()

            // the cache must keep the superset {A, B}, not shrink to {A}
            assertEquals(2, db.recordCache().byId(1)!!.recordCount, "partial strip must not shrink the cache")
        }

    @Test
    fun legacyEmptyHashRowIsRescannedAndBackfilled() =
        runBlocking {
            val embedder =
                cam.engram.format.jpeg
                    .JpegEmbedder(FakeXmp())
            val note = EngramRecord(RecordKind.Note, 1, "legacy".encodeToByteArray())
            val bytes = embedder.embed(SyntheticMedia.jpegPlain(), listOf(note), "legacy")
            // v2-era state migrated forward: a scanned media row whose cache row never
            // stored a content hash (the migration defaulted it to empty)
            addPhoto(1, bytes)
            db.media().upsert(
                listOf(
                    cam.engram.app.data.db.MediaItemEntity(
                        1,
                        "content://media/images/1",
                        false,
                        "image/jpeg",
                        "DCIM/Camera/",
                        1,
                        bytes.size.toLong(),
                        1,
                        1,
                        0,
                        0,
                    ),
                ),
            )
            db.recordCache().upsert(
                cam.engram.app.data.db
                    .RecordCacheEntity(1, 0, bytes.size.toLong(), note.encode(), 1, 0),
            )
            val stats = reconciler.reconcile()
            assertEquals(1, stats.scanned, "the hashless row must be rescanned")
            assertTrue(
                db
                    .recordCache()
                    .byId(1)!!
                    .contentHash
                    .isNotEmpty(),
                "the backfill stores the hash",
            )
            assertEquals(0, reconciler.reconcile().scanned, "the backfill is a one-time pass")
        }

    @Test
    fun idReuseAfterRemovalPreservesTheOrphan() =
        runBlocking {
            val embedder =
                cam.engram.format.jpeg
                    .JpegEmbedder(FakeXmp())
            val old = EngramRecord(RecordKind.Note, 1, "first life".encodeToByteArray())
            addPhoto(1, embedder.embed(SyntheticMedia.jpegPlain(), listOf(old), "first life"))
            reconciler.reconcile()

            // the photo disappears (media row deleted, cache deliberately kept)
            snapshot.clear()
            reconciler.reconcile()

            // MediaStore hands the id to a brand new capture with its own records
            val fresh = EngramRecord(RecordKind.Note, 2, "second life".encodeToByteArray())
            addPhoto(1, embedder.embed(SyntheticMedia.jpegWithFillBytes(), listOf(fresh), "second life"), takenAt = 555)
            reconciler.reconcile()

            val rows = db.recordCache().all().sortedBy { it.identityTakenAt }
            assertEquals(2, rows.size, "the old capture's cache must survive the id reuse")
            assertEquals(listOf(1L, 555L), rows.map { it.identityTakenAt })
        }

    // finding H1: a media id reused in place (no reconcile ever seeing it absent) must not
    // scan the new capture under the old one's identity and graft the old private records
    @Test
    fun directIdReuseReDerivesIdentityAndOrphansOldCacheAndEvictsIdKeyedState() =
        runBlocking {
            val embedder =
                cam.engram.format.jpeg
                    .JpegEmbedder(FakeXmp())
            // photo A: id 1, taken at 100, with its own private record
            val a = EngramRecord(RecordKind.Note, 1, "photo A private".encodeToByteArray())
            addPhoto(1, embedder.embed(SyntheticMedia.jpegPlain(), listOf(a), "photo A"), takenAt = 100)
            reconciler.reconcile()
            // A's id-keyed enrichment and draft
            db.enrichmentCache().upsert(
                cam.engram.app.data.db
                    .EnrichmentCacheEntity(1, "A".encodeToByteArray(), 0),
            )
            db.drafts().upsert(
                cam.engram.app.data.db
                    .DraftEntity(1, "A's unsaved note", null, 0),
            )

            // the same id now points at photo B, a different capture (different takenAt), and
            // no reconcile ever observed id 1 absent (direct in-place reuse)
            val b = EngramRecord(RecordKind.Note, 2, "photo B private".encodeToByteArray())
            val bBytes = embedder.embed(SyntheticMedia.jpegWithFillBytes(), listOf(b), "photo B")
            files["content://media/images/1"] = bBytes
            snapshot[0] = snapshot[0].copy(takenAtMillis = 200, sizeBytes = bBytes.size.toLong(), dateModified = 2)
            reconciler.reconcile()

            // the media row now tracks B's capture identity, not A's retained one
            assertEquals(200, db.media().byId(1)!!.takenAtMillis)
            // A's cache survives as an orphan at its own identity; B is cached under its own
            assertEquals(
                listOf(100L, 200L),
                db
                    .recordCache()
                    .all()
                    .map { it.identityTakenAt }
                    .sorted(),
                "A's cache must be orphaned, B cached separately, not merged under A's identity",
            )
            // A's id-keyed enrichment and draft must not carry onto B
            assertNull(db.enrichmentCache().byId(1), "A's enrichment must not apply to B")
            assertNull(db.drafts().byId(1), "A's draft must not apply to B")
        }

    @Test
    fun videoScanPopulatesAStreamingContentHash() =
        runBlocking {
            val a = EngramRecord(RecordKind.Note, 1, "clip".encodeToByteArray())
            val bytes =
                cam.engram.format.mp4.Mp4Codec
                    .embed(SyntheticMedia.mp4MoovLast(), listOf(a))
            addPhoto(1, bytes, mime = "video/mp4", isVideo = true)
            reconciler.reconcile()
            assertEquals(
                cam.engram.format.archive.EngramArchive
                    .contentHashName(bytes),
                db.recordCache().byId(1)!!.contentHash,
                "videos must be content-addressed at scan (streamed, not loaded whole)",
            )
        }

    @Test
    fun cacheEntryIsNamedAfterTheDisplayName() =
        runBlocking {
            val a = EngramRecord(RecordKind.Note, 1, "hi".encodeToByteArray())
            addPhoto(
                1,
                cam.engram.format.jpeg
                    .JpegEmbedder(FakeXmp())
                    .embed(SyntheticMedia.jpegPlain(), listOf(a), "hi"),
            )
            snapshot[0] = snapshot[0].copy(displayName = "IMG_0042.jpg")
            reconciler.reconcile()
            assertEquals(
                "IMG_0042.jpg",
                db.recordCache().byId(1)!!.originalName,
                "the archive entry name must be the real file name, not its folder",
            )
        }

    @Test
    fun scanCommitsMediaRowAndCacheTogether() =
        runBlocking {
            val a = EngramRecord(RecordKind.Note, 1, "a".encodeToByteArray())
            addPhoto(
                1,
                cam.engram.format.jpeg
                    .JpegEmbedder(FakeXmp())
                    .embed(SyntheticMedia.jpegPlain(), listOf(a), "a"),
            )
            // a dying record_cache insert must not leave the media row claiming the item
            // was scanned: the item stays unscanned so the next reconcile retries it
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_cache BEFORE INSERT ON record_cache BEGIN SELECT RAISE(ABORT, 'injected'); END",
            )
            runCatching { reconciler.reconcile() }
            assertEquals(-1, db.media().byId(1)!!.recordCount, "a failed cache insert must not mark the item scanned")
        }

    // the superset merge runs on the record-frame blob, not the container, so it must hold on
    // every codec, not only JPEG (finding 4, codec uniformity)
    @Test
    fun partialStripKeepsTheCachedSupersetForPng() =
        runBlocking {
            val embedder =
                cam.engram.format.png
                    .PngEmbedder(FakeXmp())
            val a = EngramRecord(RecordKind.Note, 1, "keep-a".encodeToByteArray())
            val b = EngramRecord(RecordKind.Note, 2, "keep-b".encodeToByteArray())
            addPhoto(1, embedder.embed(SyntheticMedia.png1x1(), listOf(a, b), "keep-b"), mime = "image/png")
            reconciler.reconcile()
            assertEquals(2, db.recordCache().byId(1)!!.recordCount)

            val stripped = embedder.embed(SyntheticMedia.png1x1(), listOf(a), "keep-a")
            files["content://media/images/1"] = stripped
            snapshot[0] = snapshot[0].copy(sizeBytes = stripped.size.toLong(), dateModified = 2)
            reconciler.reconcile()

            assertEquals(2, db.recordCache().byId(1)!!.recordCount, "partial strip must not shrink the cache")
        }

    @Test
    fun partialStripKeepsTheCachedSupersetForMp4() =
        runBlocking {
            val a = EngramRecord(RecordKind.Note, 1, "keep-a".encodeToByteArray())
            val b = EngramRecord(RecordKind.Note, 2, "keep-b".encodeToByteArray())
            addPhoto(
                1,
                cam.engram.format.mp4.Mp4Codec
                    .embed(SyntheticMedia.mp4MoovLast(), listOf(a, b)),
                mime = "video/mp4",
                isVideo = true,
            )
            reconciler.reconcile()
            assertEquals(2, db.recordCache().byId(1)!!.recordCount)

            val stripped =
                cam.engram.format.mp4.Mp4Codec
                    .embed(SyntheticMedia.mp4MoovLast(), listOf(a))
            files["content://media/images/1"] = stripped
            snapshot[0] = snapshot[0].copy(sizeBytes = stripped.size.toLong(), dateModified = 2)
            reconciler.reconcile()

            assertEquals(2, db.recordCache().byId(1)!!.recordCount, "partial strip must not shrink the cache")
        }
}

private class FakeXmp : cam.engram.format.xmp.XmpEngine {
    override fun apply(
        existingStandard: String?,
        existingExtended: String?,
        update: cam.engram.format.xmp.XmpUpdate,
        standardLimitBytes: Int,
    ): cam.engram.format.xmp.XmpApplyResult =
        cam.engram.format.xmp
            .XmpApplyResult("desc=${update.mirrorDescription}", null)

    override fun read(packet: String): cam.engram.format.xmp.XmpSummary =
        cam.engram.format.xmp
            .XmpSummary(false, null, null, null, null, null)
}
