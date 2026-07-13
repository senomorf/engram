package cam.engram.app.writeback

import cam.engram.format.records.AudioPayload
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure-JVM: RecordFactory turns a user annotation into wire records with content-addressed ids. */
class RecordFactoryTest {
    private val factory = RecordFactory(writerId = "test-writer", clock = { 42L })

    private fun audioFile(bytes: ByteArray): File = File.createTempFile("note", ".ogg").apply { writeBytes(bytes) }

    @Test
    fun noteOnlyProducesOneNoteRecord() {
        val records = factory.fromAnnotation(Annotation("hello", null), mediaId = 1)
        assertEquals(1, records.size)
        val note = records.single()
        assertEquals(RecordKind.Note, note.kind)
        assertEquals("hello", note.payload.decodeToString())
        assertEquals(42L, note.tsMillis)
        assertEquals("test-writer", note.writer)
        assertEquals(EngramRecord.ID_LENGTH, note.id.size)
    }

    @Test
    fun audioOnlyProducesOneAudioRecord() {
        val records =
            factory.fromAnnotation(Annotation(null, audioFile(ByteArray(16) { 7 }), "audio/ogg"), mediaId = 1)
        assertEquals(1, records.size)
        assertEquals(RecordKind.Audio, records.single().kind)
        val (mime, data) = AudioPayload.decode(records.single().payload)!!
        assertEquals("audio/ogg", mime)
        assertEquals(16, data.size)
    }

    @Test
    fun noteAndAudioProduceTwoRecordsInOrder() {
        val records = factory.fromAnnotation(Annotation("caption", audioFile(ByteArray(8) { 1 })), mediaId = 1)
        assertEquals(listOf(RecordKind.Note, RecordKind.Audio), records.map { it.kind })
        // distinct ids per record: different kind and payload hash to different content-addressed ids
        assertTrue(records[0].idHex != records[1].idHex)
    }

    @Test
    fun blankNoteIsSkipped() {
        assertTrue(factory.fromAnnotation(Annotation("   ", null), mediaId = 1).isEmpty())
    }

    @Test
    fun missingOrEmptyAudioIsSkipped() {
        assertTrue(factory.fromAnnotation(Annotation(null, File("/does/not/exist.ogg")), mediaId = 1).isEmpty())
        assertTrue(factory.fromAnnotation(Annotation(null, audioFile(ByteArray(0))), mediaId = 1).isEmpty())
    }

    // content-addressed ids are the idempotency anchor (reviewer D): the same annotation on the same
    // capture re-derives the same id, so a retried save recognizes and skips the already-written record
    @Test
    fun sameAnnotationAndMediaIdProduceStableIds() {
        val a = factory.fromAnnotation(Annotation("stable", null), mediaId = 7).single()
        val b = factory.fromAnnotation(Annotation("stable", null), mediaId = 7).single()
        assertEquals(a.idHex, b.idHex)
        // a different capture (mediaId) yields a different id, so ids never collide across photos
        val c = factory.fromAnnotation(Annotation("stable", null), mediaId = 8).single()
        assertTrue(a.idHex != c.idHex)
    }
}
