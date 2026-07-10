package cam.engram.format

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Sha256Test {
    @Test
    fun knownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.of(ByteArray(0)).toHex(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.of("abc".encodeToByteArray()).toHex(),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.of("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun paddingBoundariesMatchTheOneShot() {
        // 55, 56, 63, 64 bytes exercise every length-append path
        for (n in intArrayOf(1, 55, 56, 63, 64, 65, 127, 128, 1000)) {
            val data = ByteArray(n) { (it * 7 + n).toByte() }
            assertContentEquals(Sha256.of(data), Sha256().update(data).digest(), "length $n")
        }
    }

    @Test
    fun chunkedStreamingMatchesTheOneShot() {
        val data = ByteArray(100_003) { (it * 31).toByte() }
        val chunked = Sha256()
        var i = 0
        val steps = intArrayOf(1, 7, 63, 64, 65, 4096)
        var s = 0
        while (i < data.size) {
            val take = minOf(steps[s % steps.size], data.size - i)
            chunked.update(data, i, take)
            i += take
            s++
        }
        assertContentEquals(Sha256.of(data), chunked.digest())
    }
}
