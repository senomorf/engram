package photos.engram.format

import kotlin.test.Test
import kotlin.test.assertEquals

class Md5Test {
    @Test
    fun rfc1321Vectors() {
        assertEquals("D41D8CD98F00B204E9800998ECF8427E", Md5.hexUpper(Md5.of(ByteArray(0))))
        assertEquals("900150983CD24FB0D6963F7D28E17F72", Md5.hexUpper(Md5.of("abc".encodeToByteArray())))
        assertEquals(
            "57EDF4A22BE3C955AC49DA2E2107B67A",
            Md5.hexUpper(
                Md5.of(
                    "12345678901234567890123456789012345678901234567890123456789012345678901234567890"
                        .encodeToByteArray(),
                ),
            ),
        )
    }

    @Test
    fun multiBlockInput() {
        // forces more than one 64-byte block plus padding boundary handling
        val input = ByteArray(200) { (it % 251).toByte() }
        assertEquals(32, Md5.hexUpper(Md5.of(input)).length)
        assertEquals(Md5.hexUpper(Md5.of(input)), Md5.hexUpper(Md5.of(input.copyOf())))
    }
}
