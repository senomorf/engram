package cam.engram.format.jpeg

import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Test
    fun upsertPreservesOtherIimDatasets() {
        val keywords = byteArrayOf(0x1C, 0x02, 0x19, 0x00, 0x03) + "key".encodeToByteArray()
        val byline = byteArrayOf(0x1C, 0x02, 0x50, 0x00, 0x03) + "byl".encodeToByteArray()
        val iim = keywords + byline
        // an 8BIM 0x0404 resource carrying keywords (2:25) and byline (2:80), even-length data (0x10)
        val resource =
            byteArrayOf(0x38, 0x42, 0x49, 0x4D, 0x04, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10) + iim
        val updated = Iptc.upsertCaption(Iptc.APP13_HEADER + resource, "new caption")
        assertEquals("new caption", Iptc.readCaption(updated))
        assertTrue(containsSubsequence(updated, keywords), "keywords 2:25 must survive the caption upsert")
        assertTrue(containsSubsequence(updated, byline), "byline 2:80 must survive the caption upsert")
    }

    @Test
    fun upsertPreservesExtendedLengthAndLaterIimDatasets() {
        val keywords = byteArrayOf(0x1C, 0x02, 0x19, 0x00, 0x03) + "key".encodeToByteArray()
        // an extended-length dataset: high bit set, 2 length octets (0x00,0x04) giving length 4
        val extended =
            byteArrayOf(0x1C, 0x02, 0xC8.toByte(), 0x80.toByte(), 0x02, 0x00, 0x04) + "eeee".encodeToByteArray()
        val byline = byteArrayOf(0x1C, 0x02, 0x50, 0x00, 0x03) + "byl".encodeToByteArray()
        val iim = keywords + extended + byline
        val updated = Iptc.upsertCaption(Iptc.APP13_HEADER + photoshopResource(0x0404, iim), "new caption")
        assertEquals("new caption", Iptc.readCaption(updated))
        assertTrue(containsSubsequence(updated, keywords), "the dataset before the extended one must survive")
        assertTrue(containsSubsequence(updated, extended), "the extended-length dataset must survive byte-exact")
        assertTrue(containsSubsequence(updated, byline), "the dataset after the extended one must survive")
    }

    @Test
    fun upsertLeavesApp13UntouchedWhenIimCannotBeCarried() {
        // an extended-length dataset whose length octets claim far more bytes than exist:
        // the IIM cannot be safely carried, so upsert must skip the mirror and leave the
        // whole APP13 payload byte-for-byte rather than drop datasets and delete the backup
        val brokenIim =
            byteArrayOf(0x1C, 0x02, 0xC8.toByte(), 0x80.toByte(), 0x02, 0x7F, 0xFF.toByte()) + "x".encodeToByteArray()
        val payload = Iptc.APP13_HEADER + photoshopResource(0x0404, brokenIim)
        val updated = Iptc.upsertCaption(payload, "new caption")
        assertContentEquals(payload, updated, "a caption upsert must not rewrite an IIM it cannot safely carry")
    }

    // 8BIM resource with an empty pascal name and a 4-byte big-endian data length
    private fun photoshopResource(
        id: Int,
        data: ByteArray,
    ): ByteArray {
        val header = byteArrayOf(0x38, 0x42, 0x49, 0x4D, (id ushr 8).toByte(), (id and 0xFF).toByte(), 0, 0)
        val len =
            byteArrayOf(
                (data.size ushr 24).toByte(),
                (data.size ushr 16).toByte(),
                (data.size ushr 8).toByte(),
                (data.size and 0xFF).toByte(),
            )
        val padded = if (data.size % 2 != 0) data + byteArrayOf(0) else data
        return header + len + padded
    }

    private fun containsSubsequence(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[start + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
