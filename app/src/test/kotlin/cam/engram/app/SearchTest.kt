package cam.engram.app

import androidx.test.core.app.ApplicationProvider
import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.media.SourceItem
import cam.engram.app.data.scan.RecordScanner
import cam.engram.app.domain.Reconciler
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import cam.engram.format.xmp.XmpApplyResult
import cam.engram.format.xmp.XmpEngine
import cam.engram.format.xmp.XmpSummary
import cam.engram.format.xmp.XmpUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SearchTest {
    private val db = EngramDb.inMemory(ApplicationProvider.getApplicationContext())
    private val access = FakeContentAccess()
    private val snapshot = mutableListOf<SourceItem>()

    private val source =
        object : cam.engram.app.data.media.MediaSource {
            override suspend fun snapshot(includeScreenshots: Boolean) = snapshot.toList()
        }

    private val reconciler =
        Reconciler(db, source, RecordScanner(access), { true }, Dispatchers.Unconfined, clock = { 1L })

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
            SourceItem(id, uri, false, "image/jpeg", "DCIM/Camera/", id, access.files[uri]!!.size.toLong(), id)
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
                SourceItem(2, "content://media/2", false, "image/jpeg", "DCIM/Camera/", 2, 50, 2)
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
