package photos.engram.format.xmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XmpCoreEngineTest {

    private val engine = XmpCoreEngine()

    @Test
    fun freshPacket() {
        val packet = engine.apply(null, XmpUpdate("moon walk", 1234, 2))
        val s = engine.read(packet)
        assertTrue(s.hasEngram)
        assertEquals("0.1", s.specVersion)
        assertEquals(1234L, s.payloadLength)
        assertEquals(2, s.recordCount)
        assertEquals("moon walk", s.description)
        assertTrue(packet.contains("ns.engram.photos"))
    }

    @Test
    fun foreignPropertiesSurviveMerge() {
        val foreign = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
                    hdrgm:Version="1.0"/>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val merged = engine.apply(foreign, XmpUpdate("kept", 10, 1))
        assertTrue(merged.contains("hdr-gain-map"), "camera hdr metadata must survive our merge")
        val s = engine.read(merged)
        assertEquals("kept", s.description)
        assertTrue(s.hasEngram)
    }

    @Test
    fun secondApplyOverwritesOwnPropsOnly() {
        val first = engine.apply(null, XmpUpdate("v1", 100, 1))
        val second = engine.apply(first, XmpUpdate("v2", 200, 2))
        val s = engine.read(second)
        assertEquals("v2", s.description)
        assertEquals(200L, s.payloadLength)
        assertEquals(2, s.recordCount)
    }
}
