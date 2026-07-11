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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ArchiveExporterTest {
    private val db = EngramDb.inMemory(ApplicationProvider.getApplicationContext())
    private val access = FakeContentAccess()
    private val exporter = ArchiveExporter(db, access, Dispatchers.Unconfined)

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
    fun recordLogSidecarRoundTripsOpaqueFramesByteExact() =
        runBlocking {
            val note = EngramRecord(RecordKind.Note, 1, "kept".encodeToByteArray())
            val opaque = SyntheticMedia.unknownVersionFrame()
            val bytes = SyntheticMedia.jpegPlain()
            val uri = "content://media/3"
            access.files[uri] = bytes
            db.media().upsert(
                listOf(
                    MediaItemEntity(3, uri, false, "image/jpeg", "DCIM/Camera/", 3, bytes.size.toLong(), 3, 2, 0, 0),
                ),
            )
            // the cache blob holds a typed note plus a future-version frame
            db.recordCache().upsert(
                RecordCacheEntity(3, 3, bytes.size.toLong(), note.encode() + opaque, 2, 0),
            )
            val written = mutableMapOf<String, ByteArray>()
            val result =
                exporter.exportTo { name, data ->
                    written[name] = data
                    true
                }
            assertEquals(1, result.itemCount)
            val hash = EngramArchive.contentHashName(bytes)
            val log = written["$hash.records"] ?: error("record log sidecar missing: ${written.keys}")
            kotlin.test.assertContentEquals(note.encode() + opaque, log, "the log must be byte-exact")
            assertEquals(2, RecordStream.decodeSequence(log).size)
        }

    @Test
    fun opaqueOnlyCacheEntryStillExports() =
        runBlocking {
            val opaque = SyntheticMedia.unknownKindFrame()
            val bytes = SyntheticMedia.jpegPlain()
            val uri = "content://media/4"
            access.files[uri] = bytes
            db.media().upsert(
                listOf(
                    MediaItemEntity(4, uri, false, "image/jpeg", "DCIM/Camera/", 4, bytes.size.toLong(), 4, 1, 0, 0),
                ),
            )
            db.recordCache().upsert(RecordCacheEntity(4, 4, bytes.size.toLong(), opaque, 1, 0))
            val written = mutableMapOf<String, ByteArray>()
            val result =
                exporter.exportTo { name, data ->
                    written[name] = data
                    true
                }
            assertEquals(1, result.itemCount, "future-format-only memories must not be skipped")
            val hash = EngramArchive.contentHashName(bytes)
            kotlin.test.assertContentEquals(opaque, written["$hash.records"]!!)
        }

    @Test
    fun manifestInventoriesEveryWrittenFileWithItsHash() =
        runBlocking {
            val bytes = SyntheticMedia.jpegPlain()
            seed(5, bytes, listOf(EngramRecord(RecordKind.Note, 1, "hi".encodeToByteArray())))
            val written = mutableMapOf<String, ByteArray>()
            exporter.exportTo { name, data ->
                written[name] = data
                true
            }
            val manifest = written["manifest.json"]!!.decodeToString()
            written.filterKeys { it != "manifest.json" }.forEach { (name, data) ->
                val expected = """{"name":"$name","sha256":"${EngramArchive.contentHashName(data)}"}"""
                assertTrue(manifest.contains(expected), "manifest must list $name with its hash: $manifest")
            }
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

    @Test
    fun cacheBlobWithDamagedMagicHeadStillExportsSurvivors() =
        runBlocking {
            // a cache blob whose head frame lost its magic used to read as empty and
            // silently skip the entry; the survivor behind the damage must still export
            val note = EngramRecord(RecordKind.Note, 1, "kept".encodeToByteArray())
            val bytes = SyntheticMedia.jpegPlain()
            val uri = "content://media/9"
            access.files[uri] = bytes
            db.media().upsert(
                listOf(
                    MediaItemEntity(9, uri, false, "image/jpeg", "DCIM/Camera/", 9, bytes.size.toLong(), 9, 1, 0, 0),
                ),
            )
            db.recordCache().upsert(
                RecordCacheEntity(
                    9,
                    9,
                    bytes.size.toLong(),
                    SyntheticMedia.frameWithDamagedMagic() + note.encode(),
                    2,
                    0,
                ),
            )
            val written = mutableMapOf<String, ByteArray>()
            val result =
                exporter.exportTo { name, data ->
                    written[name] = data
                    true
                }
            assertEquals(1, result.itemCount, "the damaged head must not skip the whole entry")
            assertContentEquals(
                note.encode(),
                written["${EngramArchive.contentHashName(bytes)}.records"],
                "the record log carries exactly the surviving frame",
            )
        }

    @Test
    fun staleIdentityRowExportsAsOrphanNotUnderLiveHash() =
        runBlocking {
            // the media id was reused: the live file is a different capture, so the old
            // cache row must export under ITS stored hash and name, never the new file's
            val liveBytes = SyntheticMedia.jpegPlain()
            val uri = "content://media/21"
            access.files[uri] = liveBytes
            db.media().upsert(
                listOf(
                    MediaItemEntity(21, uri, false, "image/jpeg", "DCIM/Camera/", 999, 10, 21, 0, 0, 0),
                ),
            )
            db.recordCache().upsert(
                RecordCacheEntity(
                    mediaId = 21,
                    identityTakenAt = 100,
                    sizeBytesAtScan = 10,
                    recordsBlob = EngramRecord(RecordKind.Note, 1, "displaced".encodeToByteArray()).encode(),
                    recordCount = 1,
                    updatedMillis = 0,
                    originalName = "before.jpg",
                    contentHash = "aaaa1111",
                ),
            )
            val written = mutableMapOf<String, ByteArray>()
            val result =
                exporter.exportTo { name, data ->
                    written[name] = data
                    true
                }
            assertEquals(1, result.itemCount)
            assertTrue(written.containsKey("aaaa1111.json"), "the displaced capture keeps its own identity")
            assertFalse(
                written.containsKey("${EngramArchive.contentHashName(liveBytes)}.json"),
                "the old blob must not be grafted onto the new capture's hash",
            )
        }

    @Test
    fun orphanWithEmptyHashExportsUnderBlobHash() =
        runBlocking {
            // a pre-hash cache row whose media is gone: rather than dropping the only
            // copy forever, the entry is named by its record log's own hash and flagged
            val blob = EngramRecord(RecordKind.Note, 1, "legacy orphan".encodeToByteArray()).encode()
            db.recordCache().upsert(
                RecordCacheEntity(31, 100, 10, blob, 1, 0, originalName = "gone.jpg"),
            )
            val written = mutableMapOf<String, ByteArray>()
            val result =
                exporter.exportTo { name, data ->
                    written[name] = data
                    true
                }
            assertEquals(1, result.itemCount, "the orphan exports instead of failing")
            assertEquals(0, result.failedCount)
            val hash = EngramArchive.contentHashName(blob)
            assertTrue(written.containsKey("$hash.json"), "named by the record log's own hash")
            assertTrue(
                written["$hash.json"]!!.decodeToString().contains("\"sourceHashKnown\":false"),
                "the entry declares its source hash unknown",
            )
        }

    @Test
    fun cacheOrphanWithStoredHashExportsMediaLess() =
        runBlocking {
            // reconcile removed the media row but kept the cache (a moved/deleted media file); the
            // hash + name stored at scan time let it export media-less rather than silently skip
            db.recordCache().upsert(
                RecordCacheEntity(
                    mediaId = 9,
                    identityTakenAt = 9,
                    sizeBytesAtScan = 0,
                    recordsBlob =
                        RecordStream.encode(
                            listOf(EngramRecord(RecordKind.Note, 1, "orphan".encodeToByteArray())),
                        ),
                    recordCount = 1,
                    updatedMillis = 0,
                    originalName = "DCIM/Camera/",
                    contentHash = "abc123",
                ),
            )
            val written = mutableMapOf<String, ByteArray>()
            val result =
                exporter.exportTo { name, data ->
                    written[name] = data
                    true
                }
            assertEquals(1, result.itemCount, "a cache orphan with a stored hash still exports")
            assertEquals(0, result.failedCount)
            assertTrue(written.containsKey("abc123.json"), "the media-less entry is named by the stored hash")
        }

    // finding E: identical media bytes are one archive identity (spec sec 11), so two
    // library rows with the same content hash must merge their logs into one entry
    // instead of the second row silently overwriting the first's files
    @Test
    fun sameHashRowsMergeIntoOneSupersetEntry() =
        runBlocking {
            val bytes = SyntheticMedia.jpegPlain()
            val noteA = EngramRecord(RecordKind.Note, 1, "note a".encodeToByteArray(), ByteArray(16) { 1 })
            val noteB = EngramRecord(RecordKind.Note, 2, "note b".encodeToByteArray(), ByteArray(16) { 2 })
            seed(6, bytes, listOf(noteA))
            seed(7, bytes, listOf(noteB))
            val written = mutableMapOf<String, ByteArray>()
            val writesPerName = mutableMapOf<String, Int>()
            val result =
                exporter.exportTo { name, data ->
                    written[name] = data
                    writesPerName[name] = (writesPerName[name] ?: 0) + 1
                    true
                }
            assertEquals(1, result.itemCount, "identical bytes are one archive identity")
            assertEquals(0, result.failedCount)
            assertTrue(writesPerName.values.all { it == 1 }, "no entry file may be written twice: $writesPerName")
            val log = written["${EngramArchive.contentHashName(bytes)}.records"]!!
            val ids = RecordStream.decodeSequence(log).map { it.decoded.record!!.idHex }
            assertEquals(2, ids.size, "both memories survive in one merged log")
            assertEquals(setOf(noteA.idHex, noteB.idHex), ids.toSet())
        }

    @Test
    fun sameHashIdenticalLogsExportOnce() =
        runBlocking {
            val bytes = SyntheticMedia.jpegPlain()
            val note = EngramRecord(RecordKind.Note, 1, "same".encodeToByteArray(), ByteArray(16) { 3 })
            seed(8, bytes, listOf(note))
            seed(9, bytes, listOf(note))
            val written = mutableMapOf<String, ByteArray>()
            val result =
                exporter.exportTo { name, data ->
                    written[name] = data
                    true
                }
            assertEquals(1, result.itemCount)
            assertEquals(0, result.failedCount)
            kotlin.test.assertContentEquals(
                note.encode(),
                written["${EngramArchive.contentHashName(bytes)}.records"]!!,
                "identical logs merge unchanged",
            )
        }

    @Test
    fun manifestWriteFailureFailsTheExport() =
        runBlocking {
            seed(1, SyntheticMedia.jpegPlain(), listOf(EngramRecord(RecordKind.Note, 1, "hi".encodeToByteArray())))
            // the item writes fine but the manifest write is dropped
            val result = exporter.exportTo { name, _ -> name != "manifest.json" }
            assertTrue(result.failedCount > 0, "a dropped manifest write must be surfaced as a failure")
        }

    @Test
    fun exportRunsOnTheInjectedDispatcher() =
        runBlocking {
            seed(41, SyntheticMedia.jpegPlain(), listOf(EngramRecord(RecordKind.Note, 1, "io".encodeToByteArray())))
            val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "export-io") }
            val threads = mutableSetOf<String>()
            val result =
                ArchiveExporter(db, access, exec.asCoroutineDispatcher()).exportTo { _, _ ->
                    threads += Thread.currentThread().name
                    true
                }
            exec.shutdown()
            // the coroutine debug agent may suffix the name with " @coroutine#N"
            assertTrue(threads.isNotEmpty() && threads.all { it.startsWith("export-io") }, threads.toString())
            assertEquals(1, result.itemCount)
        }
}
