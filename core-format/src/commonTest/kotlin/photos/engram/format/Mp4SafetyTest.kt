package photos.engram.format

import photos.engram.format.mp4.Mp4Caption
import photos.engram.format.mp4.Mp4Codec
import photos.engram.format.mp4.Mp4FormatException
import photos.engram.format.records.EngramRecord
import photos.engram.format.records.RecordKind
import photos.engram.format.testing.SyntheticMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Mp4SafetyTest {
    private val note = listOf(EngramRecord(RecordKind.Note, 1, "clip".encodeToByteArray()))

    @Test
    fun nonTailEngramBoxRefused() {
        val withBox = Mp4Codec.embed(SyntheticMedia.mp4Minimal(), note)
        val freeTail = ByteArrayBuilder()
        freeTail.appendU32be(12).append("free".encodeToByteArray()).append("tail".encodeToByteArray())
        val nonTail = withBox + freeTail.toByteArray()
        assertFailsWith<Mp4FormatException> { Mp4Codec.embed(nonTail, note) }
    }

    @Test
    fun largesizeBoxParsesAndEmbeds() {
        val src = SyntheticMedia.mp4WithLargesizeMdat()
        val boxes = Mp4Codec.topLevel(src)
        assertEquals(listOf("ftyp", "mdat"), boxes.map { it.type })
        assertEquals(16, boxes[1].headerLength)
        val out = Mp4Codec.embed(src, note)
        assertEquals(1, Mp4Codec.readRecords(out).count { it.decoded.crcOk })
    }

    @Test
    fun captionWrittenWhenMoovLast() {
        val out = Mp4Caption.tryWrite(SyntheticMedia.mp4MoovLast(), "night walk")
        assertNotNull(out)
        assertEquals("night walk", Mp4Caption.readCaption(out))
        // caption then records: both must survive
        val withRecords = Mp4Codec.embed(out, note)
        assertEquals("night walk", Mp4Caption.readCaption(withRecords))
        assertEquals(1, Mp4Codec.readRecords(withRecords).size)
    }

    @Test
    fun captionReplacedOnSecondWrite() {
        val once = Mp4Caption.tryWrite(SyntheticMedia.mp4MoovLast(), "v1")!!
        val twice = Mp4Caption.tryWrite(once, "v2")!!
        assertEquals("v2", Mp4Caption.readCaption(twice))
    }

    @Test
    fun captionRefusedWhenMoovNotLast() {
        // moov-first layout: growing moov would shift mdat and break chunk offsets
        val moovLast = SyntheticMedia.mp4MoovLast()
        val boxes = Mp4Codec.topLevel(moovLast)
        val moov = boxes.last()
        val mdatAndFtyp = moovLast.copyOfRange(0, moov.offset.toInt())
        val moovBytes = moovLast.copyOfRange(moov.offset.toInt(), moovLast.size)
        val moovFirst =
            moovLast.copyOfRange(0, 20) + moovBytes + mdatAndFtyp.copyOfRange(20, mdatAndFtyp.size)
        assertTrue(Mp4Caption.tryWrite(moovFirst, "x") == null)
    }
}
