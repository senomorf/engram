package cam.engram.format.mp4

import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers the streaming JVM MP4 write path (Mp4Files), including the append-only
 * guarantee: a second append must preserve every prior record byte-for-byte and
 * only add new ones after them.
 */
class Mp4FilesTest {
    private fun tmp(bytes: ByteArray): File =
        Files.createTempFile("engram-in", ".mp4").toFile().apply { writeBytes(bytes) }

    private fun out(): File = Files.createTempFile("engram-out", ".mp4").toFile().apply { delete() }

    private fun note(
        ts: Long,
        text: String,
        id: Byte,
        writer: String,
    ) = EngramRecord(RecordKind.Note, ts, text.encodeToByteArray(), ByteArray(EngramRecord.ID_LENGTH) { id }, writer)

    @Test
    fun appendThenReadRoundTrips() {
        val src = tmp(SyntheticMedia.mp4Minimal())
        val dst = out()
        val outcome = Mp4Files.appendRecords(src, dst, listOf(note(1, "one", 0x11, "w1")))
        assertEquals(CaptionOutcome.NOT_REQUESTED, outcome)
        val hits = Mp4Files.readRecords(dst).filter { it.decoded.crcOk }
        assertEquals(1, hits.size)
        assertEquals(
            "one",
            hits[0]
                .decoded.record!!
                .payload
                .decodeToString(),
        )
    }

    @Test
    fun secondAppendAccumulatesAndPreservesPriorRecordsExactly() {
        val src = tmp(SyntheticMedia.mp4Minimal())
        val first = out()
        Mp4Files.appendRecords(src, first, listOf(note(1, "one", 0x11, "w1")))
        val second = out()
        Mp4Files.appendRecords(first, second, listOf(note(2, "two", 0x22, "w2")))

        val hits = Mp4Files.readRecords(second).filter { it.decoded.crcOk }
        assertEquals(2, hits.size)
        // append-only: prior record unchanged (id, writer, ts, payload) and still first
        val prior = hits[0].decoded.record!!
        assertContentEquals(ByteArray(EngramRecord.ID_LENGTH) { 0x11 }, prior.id)
        assertEquals("w1", prior.writer)
        assertEquals(1L, prior.tsMillis)
        assertEquals("one", prior.payload.decodeToString())
        // and the new record is appended after it
        assertEquals(
            "two",
            hits[1]
                .decoded.record!!
                .payload
                .decodeToString(),
        )
    }

    @Test
    fun captionWrittenWhenMoovIsLast() {
        val src = tmp(SyntheticMedia.mp4MoovLast())
        val dst = out()
        val outcome = Mp4Files.appendRecords(src, dst, listOf(note(3, "x", 0x33, "w")), "sea cliff")
        assertEquals(CaptionOutcome.WRITTEN, outcome)
        assertEquals("sea cliff", Mp4Caption.readCaption(dst.readBytes()))
    }

    @Test
    fun captionSkippedWhenNoTrailingMoov() {
        val src = tmp(SyntheticMedia.mp4Minimal())
        val dst = out()
        val outcome = Mp4Files.appendRecords(src, dst, listOf(note(4, "y", 0x44, "w")), "ignored")
        assertEquals(CaptionOutcome.SKIPPED_UNSAFE_LAYOUT, outcome)
    }

    @Test
    fun materializesZeroSizeLastBoxBeforeAppending() {
        val src = tmp(SyntheticMedia.mp4Minimal(withZeroSizeLastBox = true))
        val dst = out()
        Mp4Files.appendRecords(src, dst, listOf(note(5, "z", 0x55, "w")))
        val hits = Mp4Files.readRecords(dst).filter { it.decoded.crcOk }
        assertEquals(1, hits.size)
        // the engram box must be the trailing box after the materialized mdat
        assertTrue(Mp4Codec.isEngramBox(Mp4Files.topLevel(dst).last()))
    }

    @Test
    fun readRecordsIsEmptyWithoutEngramBox() {
        val plain = tmp(SyntheticMedia.mp4Minimal())
        assertEquals(0, Mp4Files.readRecords(plain).size)
    }

    @Test
    fun rejectsExistingEngramBoxNotAtEnd() {
        val withEngram = out()
        Mp4Files.appendRecords(tmp(SyntheticMedia.mp4Minimal()), withEngram, listOf(note(1, "one", 0x11, "w")))
        // append a trailing free box so the engram box is no longer the last box
        val freeBox =
            byteArrayOf(0, 0, 0, 8, 'f'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(), 'e'.code.toByte())
        val notLast = out().apply { writeBytes(withEngram.readBytes() + freeBox) }
        assertFailsWith<Mp4FormatException> {
            Mp4Files.appendRecords(notLast, out(), listOf(note(2, "two", 0x22, "w")))
        }
    }

    @Test
    fun rejectsEmptyRecordsAndSelfOverwrite() {
        val src = tmp(SyntheticMedia.mp4Minimal())
        assertFailsWith<IllegalArgumentException> { Mp4Files.appendRecords(src, out(), emptyList()) }
        assertFailsWith<IllegalArgumentException> { Mp4Files.appendRecords(src, src, listOf(note(6, "q", 0x66, "w"))) }
    }
}
