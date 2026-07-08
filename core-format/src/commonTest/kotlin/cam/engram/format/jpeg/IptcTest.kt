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
    fun readsNullWhenIptcResourceHasNoCaptionDataset() {
        // an IPTC resource (0x0404) carrying only a 1:90 dataset, no 2:120 caption
        val iim = byteArrayOf(0x1C, 0x01, 0x5A, 0x00, 0x01, 0x42)
        val resource =
            byteArrayOf(0x38, 0x42, 0x49, 0x4D, 0x04, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x06) + iim
        assertNull(Iptc.readCaption(Iptc.APP13_HEADER + resource))
    }

    @Test
    fun upsertPreservesOtherPhotoshopResources() {
        // a non-IPTC 8BIM resource (id 0x0405, empty name, 2 data bytes) must survive the caption upsert
        val other =
            byteArrayOf(0x38, 0x42, 0x49, 0x4D, 0x04, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x0A, 0x0B)
        val updated = Iptc.upsertCaption(Iptc.APP13_HEADER + other, "kept caption")
        assertEquals("kept caption", Iptc.readCaption(updated))
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
