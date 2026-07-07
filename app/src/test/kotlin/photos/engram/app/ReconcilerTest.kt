package photos.engram.app

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import photos.engram.app.data.db.EngramDb
import photos.engram.app.data.media.ContentAccess
import photos.engram.app.data.media.MediaSource
import photos.engram.app.data.media.SourceItem
import photos.engram.app.data.scan.RecordScanner
import photos.engram.app.domain.Reconciler
import photos.engram.format.records.EngramRecord
import photos.engram.format.records.RecordKind
import photos.engram.format.testing.SyntheticMedia
import java.nio.channels.SeekableByteChannel
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ReconcilerTest {
    private val db = EngramDb.inMemory(ApplicationProvider.getApplicationContext())

    private val files = mutableMapOf<String, ByteArray>()
    private val snapshot = mutableListOf<SourceItem>()

    private val access =
        object : ContentAccess {
            override fun readBytes(uri: String): ByteArray? = files[uri]

            override fun <T> withChannel(
                uri: String,
                block: (SeekableByteChannel) -> T,
            ): T? = null
        }

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
            clock = { 1000L },
        )

    @After
    fun tearDown() {
        db.close()
    }

    private fun addPhoto(
        id: Long,
        bytes: ByteArray,
    ) {
        val uri = "content://media/images/$id"
        files[uri] = bytes
        snapshot +=
            SourceItem(
                mediaId = id,
                uri = uri,
                isVideo = false,
                mime = "image/jpeg",
                relativePath = "DCIM/Camera/",
                takenAtMillis = id,
                sizeBytes = bytes.size.toLong(),
            )
    }

    @Test
    fun addsScansAndCountsItems() =
        runBlocking {
            addPhoto(1, SyntheticMedia.jpegPlain())
            val annotated =
                photos.engram.format.jpeg
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
}

private class FakeXmp : photos.engram.format.xmp.XmpEngine {
    override fun apply(
        existingStandard: String?,
        existingExtended: String?,
        update: photos.engram.format.xmp.XmpUpdate,
        standardLimitBytes: Int,
    ): photos.engram.format.xmp.XmpApplyResult =
        photos.engram.format.xmp
            .XmpApplyResult("desc=${update.mirrorDescription}", null)

    override fun read(packet: String): photos.engram.format.xmp.XmpSummary =
        photos.engram.format.xmp
            .XmpSummary(false, null, null, null, null, null)
}
