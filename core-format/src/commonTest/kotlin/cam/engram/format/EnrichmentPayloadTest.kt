package cam.engram.format

import cam.engram.format.records.EnrichmentPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnrichmentPayloadTest {
    @Test
    fun roundTripPreservesOrderAndProvenance() {
        val payload =
            EnrichmentPayload(
                linkedMapOf(
                    EnrichmentPayload.KEY_PLACE to "Тбилиси",
                    EnrichmentPayload.KEY_WEATHER to "clear",
                    EnrichmentPayload.KEY_TEMP_C to "23.5",
                    EnrichmentPayload.KEY_SOURCE to "open-meteo",
                    EnrichmentPayload.KEY_FETCHED_AT to "1783000000000",
                ),
            )
        val decoded = EnrichmentPayload.decode(payload.encode())!!
        assertEquals("Тбилиси", decoded.fields[EnrichmentPayload.KEY_PLACE])
        assertEquals("23.5", decoded.fields[EnrichmentPayload.KEY_TEMP_C])
        assertEquals("open-meteo", decoded.fields[EnrichmentPayload.KEY_SOURCE])
        assertEquals(payload.fields.keys.toList(), decoded.fields.keys.toList())
    }

    @Test
    fun truncatedPayloadRejected() {
        val bytes = EnrichmentPayload(mapOf("a" to "b")).encode()
        assertNull(EnrichmentPayload.decode(bytes.copyOfRange(0, bytes.size - 1)))
    }

    @Test
    fun trailingBytesRejected() {
        // extra bytes after the declared fields signal corruption (review F14)
        val bytes = EnrichmentPayload(mapOf("a" to "b")).encode() + byteArrayOf(0, 0)
        assertNull(EnrichmentPayload.decode(bytes))
    }
}
