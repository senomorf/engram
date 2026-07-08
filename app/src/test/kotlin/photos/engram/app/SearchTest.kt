package photos.engram.app

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import photos.engram.app.data.db.EngramDb
import photos.engram.app.data.media.SourceItem
import photos.engram.app.data.scan.RecordScanner
import photos.engram.app.domain.Reconciler
import photos.engram.format.jpeg.JpegEmbedder
import photos.engram.format.records.EngramRecord
import photos.engram.format.records.RecordKind
import photos.engram.format.testing.SyntheticMedia
import photos.engram.format.xmp.XmpApplyResult
import photos.engram.format.xmp.XmpEngine
import photos.engram.format.xmp.XmpSummary
import photos.engram.format.xmp.XmpUpdate
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SearchTest {
    private val db = EngramDb.inMemory(ApplicationProvider.getApplicationContext())
    private val access = FakeContentAccess()
    private val snapshot = mutableListOf<SourceItem>()

    private val source =
        object : photos.engram.app.data.media.MediaSource {
            override suspend fun snapshot(includeScreenshots: Boolean) = snapshot.toList()
        }

    private val reconciler =
        Reconciler(db, source, RecordScanner(access), { true }, { 1L })

    @After
    fun tearDown() = db.close()

    private fun photoWith(note: String): ByteArray =
        JpegEmbedder(PassThroughXmp()).embed(
            SyntheticMedia.jpegPlain(),
            listOf(EngramRecord(RecordKind.Note, 1, note.encodeToByteArray())),
            note,
        )

    private fun seed(
        id: Long,
        note: String,
    ) {
        val uri = "content://media/$id"
        access.files[uri] = photoWith(note)
        snapshot +=
            SourceItem(id, uri, false, "image/jpeg", "DCIM/Camera/", id, access.files[uri]!!.size.toLong())
    }

    @Test
    fun searchFindsByNoteText() =
        runBlocking {
            seed(1, "sunrise over the lake")
            seed(2, "birthday cake candles")
            reconciler.reconcile()

            assertEquals(listOf(1L), db.search().search("sunrise*").map { it.mediaId })
            assertEquals(listOf(2L), db.search().search("cake*").map { it.mediaId })
            assertEquals(emptyList(), db.search().search("mountain*").map { it.mediaId })
        }

    @Test
    fun timelineOnlyHasAnnotated() =
        runBlocking {
            seed(1, "with a memory")
            access.files["content://media/2"] = SyntheticMedia.jpegPlain()
            snapshot +=
                SourceItem(2, "content://media/2", false, "image/jpeg", "DCIM/Camera/", 2, 50)
            reconciler.reconcile()

            val rows = db.media().timeline().first()
            assertEquals(listOf(1L), rows.map { it.mediaId })
        }
}

private class PassThroughXmp : XmpEngine {
    override fun apply(
        existingStandard: String?,
        existingExtended: String?,
        update: XmpUpdate,
        standardLimitBytes: Int,
    ): XmpApplyResult = XmpApplyResult("desc=${update.mirrorDescription}", null)

    override fun read(packet: String): XmpSummary = XmpSummary(false, null, null, null, null, null)
}
