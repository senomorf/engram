package photos.engram.format

import kotlin.test.Test
import kotlin.test.assertEquals

class Crc32Test {
    @Test
    fun knownVector() {
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
    }

    @Test
    fun rangeVariant() {
        val padded = "xx123456789yy".encodeToByteArray()
        assertEquals(0xCBF43926L, Crc32.of(padded, 2, 11))
    }
}
