package cam.engram.format

import cam.engram.format.png.PngChunk
import cam.engram.format.png.PngCodec
import cam.engram.format.png.PngEmbedder
import cam.engram.format.png.PngFile
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PngStrictTest {
    @Test
    fun trailingBytesInChunkRejectRecord() {
        val out =
            PngEmbedder(FakeXmpEngine()).embed(
                SyntheticMedia.png1x1(),
                listOf(EngramRecord(RecordKind.Note, 1, "s".encodeToByteArray())),
                "s",
            )
        val file = PngCodec.parse(out)
        val chunks =
            file.chunks.map { c ->
                if (c.type == PngCodec.ENGRAM_CHUNK) PngChunk(c.type, c.data + byteArrayOf(0x00)) else c
            }
        val corrupted = PngCodec.parse(PngCodec.serialize(PngFile(chunks, file.trailer)))
        assertEquals(1, PngCodec.engramChunkCount(corrupted))
        assertEquals(0, PngCodec.engramRecords(corrupted).size, "padded chunk must not count as a clean record")
    }

    @Test
    fun compressedItxtIsNotOurXmp() {
        val kw = PngCodec.XMP_KEYWORD.encodeToByteArray()
        val data = kw + byteArrayOf(0, 1, 0, 0, 0) + "zzz".encodeToByteArray()
        assertNull(PngCodec.xmpPacket(PngChunk("iTXt", data)))
    }

    @Test
    fun crcDamageIsFlaggedNotHidden() {
        val bytes = SyntheticMedia.png1x1()
        // flip one byte inside IDAT data (signature 8 + IHDR 25 + IDAT header 8 puts data at 47)
        bytes[47] = (bytes[47].toInt() xor 0x40).toByte()
        val file = PngCodec.parse(bytes)
        assertTrue(file.chunks.any { !it.crcOk })
    }
}
