package cam.engram.format

import cam.engram.format.mp4.Mp4Codec
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Mp4Test {
    @Test
    fun carryFramesPreserveUnknownKindRecords() {
        val out =
            Mp4Codec.embed(
                SyntheticMedia.mp4MoovLast(),
                listOf(EngramRecord(RecordKind.Note, 1, "z".encodeToByteArray())),
                listOf(SyntheticMedia.unknownKindFrame()),
            )
        val hits = Mp4Codec.readRecords(out)
        assertEquals(2, hits.size)
        assertTrue(hits.any { it.decoded.crcOk && it.decoded.record == null }, "unknown-kind frame preserved")
    }

    @Test
    fun topLevelBoxes() {
        val boxes = Mp4Codec.topLevel(SyntheticMedia.mp4Minimal())
        assertEquals(listOf("ftyp", "free", "mdat"), boxes.map { it.type })
    }

    @Test
    fun embedAndRead() {
        val out =
            Mp4Codec.embed(
                SyntheticMedia.mp4Minimal(),
                listOf(EngramRecord(RecordKind.Note, 9, "clip".encodeToByteArray())),
            )
        val hits = Mp4Codec.readRecords(out)
        assertEquals(1, hits.size)
        assertTrue(hits.single().decoded.crcOk)
        assertEquals(
            "clip",
            hits
                .single()
                .decoded.record!!
                .payload
                .decodeToString(),
        )
    }

    @Test
    fun embedMergesExistingBox() {
        val once =
            Mp4Codec.embed(
                SyntheticMedia.mp4Minimal(),
                listOf(EngramRecord(RecordKind.Note, 1, "a".encodeToByteArray())),
            )
        val twice =
            Mp4Codec.embed(
                once,
                listOf(EngramRecord(RecordKind.Note, 2, "b".encodeToByteArray())),
            )
        val boxes = Mp4Codec.topLevel(twice)
        assertEquals(1, boxes.count { Mp4Codec.isEngramBox(it) })
        assertEquals(2, Mp4Codec.readRecords(twice).size)
    }

    @Test
    fun zeroSizeLastBoxGetsMaterialized() {
        val src = SyntheticMedia.mp4Minimal(withZeroSizeLastBox = true)
        val out = Mp4Codec.embed(src, listOf(EngramRecord(RecordKind.Note, 1, "z".encodeToByteArray())))
        val boxes = Mp4Codec.topLevel(out)
        assertEquals(listOf("ftyp", "free", "mdat", "uuid"), boxes.map { it.type })
        assertTrue(boxes.none { it.sizeFieldWasZero })
    }
}
