package cam.engram.format.jpeg

import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import cam.engram.format.xmp.XmpCoreEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtendedXmpJpegTest {
    private val engine = XmpCoreEngine()
    private val record = listOf(EngramRecord(RecordKind.Note, 1, "n".encodeToByteArray()))

    private fun bigCameraJpeg(): ByteArray {
        // fits a single APP1 on its own, but exceeds the limit once merged with
        // engram properties and serializer padding, forcing the split path
        val big = "C".repeat(63000)
        val packet =
            """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about="" xmlns:t="http://test.example/ns/" t:Big="$big"/>
              </rdf:RDF>
            </x:xmpmeta>
            """.trimIndent()
        return SyntheticMedia.jpegWithXmp(packet)
    }

    @Test
    fun oversizedXmpRoundTripsThroughExtendedSegments() {
        val out = JpegEmbedder(engine).embed(bigCameraJpeg(), record, "extended test")
        val parts = JpegCodec.parse(out)
        assertTrue(
            parts.filterIsInstance<Segment>().any { it.isExtendedXmpApp1() },
            "oversized packet must produce extension segments",
        )
        val reassembled = assertNotNull(ExtendedXmp.collect(parts))
        assertTrue(reassembled.packet.contains("C".repeat(100)))
        val std = parts.filterIsInstance<Segment>().first { it.isXmpApp1() }.xmpPacket()
        assertEquals(reassembled.guid, engine.read(std).extendedXmpGuid)
    }

    @Test
    fun secondEmbedPreservesExtendedContent() {
        val once = JpegEmbedder(engine).embed(bigCameraJpeg(), record, "one")
        val twice =
            JpegEmbedder(engine).embed(
                once,
                listOf(EngramRecord(RecordKind.Note, 2, "m".encodeToByteArray())),
                "two",
            )
        val reassembled = assertNotNull(ExtendedXmp.collect(JpegCodec.parse(twice)))
        assertTrue(reassembled.packet.contains("C".repeat(100)), "camera bulk data lost on rewrite")
        val std =
            JpegCodec
                .parse(twice)
                .filterIsInstance<Segment>()
                .first { it.isXmpApp1() }
                .xmpPacket()
        assertEquals("two", engine.read(std).description)
        assertEquals(2, engine.read(std).recordCount)
    }
}
