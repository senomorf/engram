package cam.engram.format.xmp

import cam.engram.format.Md5
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmpCoreEngineTest {
    private val engine = XmpCoreEngine()
    private val limit = 60000

    @Test
    fun freshPacket() {
        val result = engine.apply(null, null, XmpUpdate("moon walk", 1234, 2), limit)
        assertNull(result.extendedPacket)
        val s = engine.read(result.standardPacket)
        assertTrue(s.hasEngram)
        assertEquals("0.1", s.specVersion)
        assertEquals(1234L, s.payloadLength)
        assertEquals(2, s.recordCount)
        assertEquals("moon walk", s.description)
        assertTrue(result.standardPacket.contains("ns.engram.cam"))
    }

    @Test
    fun foreignPropertiesSurviveMerge() {
        val foreign =
            """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
                    hdrgm:Version="1.0"/>
              </rdf:RDF>
            </x:xmpmeta>
            """.trimIndent()
        val result = engine.apply(foreign, null, XmpUpdate("kept", 10, 1), limit)
        assertTrue(result.standardPacket.contains("hdr-gain-map"), "camera hdr metadata must survive our merge")
        val s = engine.read(result.standardPacket)
        assertEquals("kept", s.description)
        assertTrue(s.hasEngram)
    }

    @Test
    fun secondApplyOverwritesOwnPropsOnly() {
        val first = engine.apply(null, null, XmpUpdate("v1", 100, 1), limit)
        val second = engine.apply(first.standardPacket, null, XmpUpdate("v2", 200, 2), limit)
        val s = engine.read(second.standardPacket)
        assertEquals("v2", s.description)
        assertEquals(200L, s.payloadLength)
        assertEquals(2, s.recordCount)
    }

    @Test
    fun unparseableExistingPacketFailsClosed() {
        assertFailsWith<XmpWriteException> {
            engine.apply("<this is not xmp at all", null, XmpUpdate("x", 1, 1), limit)
        }
        assertFailsWith<XmpWriteException> {
            engine.apply(null, "<junk extended", XmpUpdate("x", 1, 1), limit)
        }
    }

    @Test
    fun oversizedPacketSplitsIntoExtendedXmp() {
        val big = "A".repeat(70000)
        val foreign =
            """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about="" xmlns:t="http://test.example/ns/" t:Big="$big"/>
              </rdf:RDF>
            </x:xmpmeta>
            """.trimIndent()
        val result = engine.apply(foreign, null, XmpUpdate("split note", 5, 1), 60000)
        val extended = assertNotNull(result.extendedPacket, "oversized packet must split")
        assertTrue(extended.contains(big), "bulk property belongs in the extended part")
        assertTrue(result.standardPacket.encodeToByteArray().size <= 60000)
        val s = engine.read(result.standardPacket)
        assertEquals("split note", s.description)
        assertTrue(s.hasEngram)
        assertEquals(Md5.hexUpper(Md5.of(extended.encodeToByteArray())), s.extendedXmpGuid)
    }

    @Test
    fun readReturnsEmptyForGarbage() {
        val s = engine.read("definitely not an xmp packet")
        assertTrue(!s.hasEngram)
        assertNull(s.description)
    }

    @Test
    fun splitKeepsExistingDescriptionWhenNoMirror() {
        val big = "C".repeat(70000)
        val foreign =
            """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:dc="http://purl.org/dc/elements/1.1/"
                    xmlns:t="http://test.example/ns/" t:Big="$big">
                  <dc:description><rdf:Alt><rdf:li xml:lang="x-default">camera desc</rdf:li></rdf:Alt></dc:description>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
            """.trimIndent()
        // mirrorDescription is null, so the split path must fall back to the existing description
        val result = engine.apply(foreign, null, XmpUpdate(null, 5, 1), 60000)
        assertNotNull(result.extendedPacket, "oversized packet must split")
        assertEquals("camera desc", engine.read(result.standardPacket).description)
    }

    @Test
    fun splitPreservesAllLocalizedDescriptions() {
        val big = "D".repeat(70000)
        val foreign =
            """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:dc="http://purl.org/dc/elements/1.1/"
                    xmlns:t="http://test.example/ns/" t:Big="$big">
                  <dc:description><rdf:Alt>
                    <rdf:li xml:lang="x-default">XD_default</rdf:li>
                    <rdf:li xml:lang="fr">FR_bonjour</rdf:li>
                    <rdf:li xml:lang="de">DE_hallo</rdf:li>
                  </rdf:Alt></dc:description>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
            """.trimIndent()
        // mirror is null, so the split must carry the existing descriptions; every language must
        // survive in the standard packet (it reads without ExtendedXMP support), not just x-default
        val result = engine.apply(foreign, null, XmpUpdate(null, 5, 1), 60000)
        assertNotNull(result.extendedPacket, "oversized packet must split")
        val std = result.standardPacket
        assertTrue(std.contains("XD_default"), "x-default description must survive the split")
        assertTrue(std.contains("FR_bonjour"), "the French description must survive the split")
        assertTrue(std.contains("DE_hallo"), "the German description must survive the split")
    }

    @Test
    fun splitAppliesMirrorAsXDefaultAndKeepsForeignLanguages() {
        val big = "E".repeat(70000)
        val foreign =
            """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:dc="http://purl.org/dc/elements/1.1/"
                    xmlns:t="http://test.example/ns/" t:Big="$big">
                  <dc:description><rdf:Alt>
                    <rdf:li xml:lang="x-default">XD_original</rdf:li>
                    <rdf:li xml:lang="fr">FR_bonjour</rdf:li>
                  </rdf:Alt></dc:description>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
            """.trimIndent()
        // our note replaces x-default, but the foreign French caption still survives the split
        val result = engine.apply(foreign, null, XmpUpdate("MIRROR_note", 5, 1), 60000)
        assertNotNull(result.extendedPacket, "oversized packet must split")
        val std = result.standardPacket
        assertEquals("MIRROR_note", engine.read(std).description, "the mirror becomes x-default")
        assertTrue(std.contains("FR_bonjour"), "the French description survives the split")
        assertTrue(!std.contains("XD_original"), "the mirror replaces the original x-default")
    }

    @Test
    fun extendedPacketMergesBackOnNextApply() {
        val big = "B".repeat(70000)
        val foreign =
            """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about="" xmlns:t="http://test.example/ns/" t:Big="$big"/>
              </rdf:RDF>
            </x:xmpmeta>
            """.trimIndent()
        val first = engine.apply(foreign, null, XmpUpdate("one", 5, 1), 60000)
        val second = engine.apply(first.standardPacket, first.extendedPacket, XmpUpdate("two", 6, 2), 60000)
        assertTrue(second.extendedPacket!!.contains(big), "bulk property must survive a second write cycle")
        assertEquals("two", engine.read(second.standardPacket).description)
    }
}
