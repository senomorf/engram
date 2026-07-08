package cam.engram.format.jpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptcTest {
    @Test
    fun upsertThenReadRoundTrips() {
        val payload = Iptc.upsertCaption(null, "sunset over the pier")
        assertEquals("sunset over the pier", Iptc.readCaption(payload))
    }

    @Test
    fun updatingExistingCaptionReplacesIt() {
        val first = Iptc.upsertCaption(null, "first caption")
        val second = Iptc.upsertCaption(first, "second caption")
        assertEquals("second caption", Iptc.readCaption(second))
    }

    @Test
    fun readsNullWhenNoIptcResource() {
        assertNull(Iptc.readCaption(Iptc.APP13_HEADER))
    }

    @Test
    fun malformedResourceBlockThrows() {
        assertFailsWith<JpegFormatException> {
            Iptc.readCaption(Iptc.APP13_HEADER + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        }
    }

    @Test
    fun longCaptionIsTruncatedToIimLimit() {
        val payload = Iptc.upsertCaption(null, "a".repeat(3000))
        val read = Iptc.readCaption(payload)!!
        assertTrue(read.length in 1..2000, "caption should be truncated, was ${read.length}")
    }
}
