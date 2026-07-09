package cam.engram.app.export

import androidx.test.core.app.ApplicationProvider
import cam.engram.app.FakeContentAccess
import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.db.RecordCacheEntity
import cam.engram.format.archive.EngramArchive
import cam.engram.format.records.AudioPayload
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.records.RecordStream
import cam.engram.format.testing.SyntheticMedia
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ArchiveExporterTest {
    private val db = EngramDb.inMemory(ApplicationProvider.getApplicationContext())
    private val access = FakeContentAccess()
    private val exporter = ArchiveExporter(db, access)

    @After
    fun tearDown() = db.close()

    private suspend fun seed(
        id: Long,
        bytes: ByteArray,
        records: List<EngramRecord>,
    ) {
        val uri = "content://media/$id"
        access.files[uri] = bytes
        db.media().upsert(
            listOf(
                MediaItemEntity(
                    id,
                    uri,
                    false,
                    "image/jpeg",
                    "DCIM/Camera/",
                    id,
                    bytes.size.toLong(),
                    id,
                    records.size,
                    0,
                    0,
                ),
            ),
        )
        db.recordCache().upsert(
            RecordCacheEntity(id, id, bytes.size.toLong(), RecordStream.encode(records), records.size, 0),
        )
    }

    @Test
    fun namesEntriesByContentHashNotMediaId() =
        runBlocking {
            val bytes = SyntheticMedia.jpegPlain()
            seed(1, bytes, listOf(EngramRecord(RecordKind.Note, 1, "hi".encodeToByteArray())))
            val written = mutableMapOf<String, ByteArray>()
            val result =
                exporter.exportTo { name, data ->
                    written[name] = data
                    true
                }

            val hash = EngramArchive.contentHashName(bytes)
            assertTrue(written.containsKey("$hash.json"), "entry must be named by the media content hash")
            assertFalse(written.containsKey("1.json"), "a device-local mediaId must not name the entry")
            assertEquals(1, result.itemCount)
            assertEquals(0, result.failedCount)
        }

    @Test
    fun failedWriteIsCountedAsFailedNotExported() =
        runBlocking {
            seed(2, SyntheticMedia.jpegPlain(), listOf(EngramRecord(RecordKind.Note, 1, "hi".encodeToByteArray())))
            // a sink that drops the item's JSON write but lets the manifest through
            val result = exporter.exportTo { name, _ -> name == "manifest.json" }

            assertEquals(0, result.itemCount)
            assertEquals(1, result.failedCount)
        }

    @Test
    fun exportsAudioAndSkipsUnreadableOrEmpty() =
        runBlocking {
            // a real item with a voice clip: its audio blob is written and counted
            seed(
                3,
                SyntheticMedia.jpegPlain(),
                listOf(
                    EngramRecord(RecordKind.Note, 1, "hi".encodeToByteArray()),
                    EngramRecord(RecordKind.Audio, 2, AudioPayload.encode("audio/ogg", ByteArray(8) { 7 })),
                ),
            )
            // an item whose media file is gone: cannot be hashed, counts as failed
            db.media().upsert(
                listOf(MediaItemEntity(4, "content://media/4", false, "image/jpeg", "DCIM/Camera/", 4, 0, 4, 1, 0, 0)),
            )
            db.recordCache().upsert(
                RecordCacheEntity(
                    4,
                    4,
                    0,
                    RecordStream.encode(listOf(EngramRecord(RecordKind.Note, 1, "x".encodeToByteArray()))),
                    1,
                    0,
                ),
            )
            // an entry whose cached blob decodes to no records: skipped, neither exported nor failed
            access.files["content://media/5"] = SyntheticMedia.jpegPlain()
            db.media().upsert(
                listOf(MediaItemEntity(5, "content://media/5", false, "image/jpeg", "DCIM/Camera/", 5, 0, 5, 0, 0, 0)),
            )
            db.recordCache().upsert(RecordCacheEntity(5, 5, 0, ByteArray(0), 0, 0))

            val written = mutableMapOf<String, ByteArray>()
            val result =
                exporter.exportTo { name, data ->
                    written[name] = data
                    true
                }

            assertEquals(1, result.itemCount, "only the readable item with records fully exports")
            assertEquals(1, result.audioCount)
            assertEquals(1, result.failedCount, "the unreadable item counts as failed")
            assertTrue(written.keys.any { it.endsWith(".ogg") || it.endsWith(".m4a") }, "the voice clip is written")
        }
}
