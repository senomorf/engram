package photos.engram.format.mp4

import photos.engram.format.ByteArrayBuilder
import photos.engram.format.records.EngramRecord
import photos.engram.format.records.RecordHit
import photos.engram.format.records.RecordStream
import photos.engram.format.u32be
import photos.engram.format.u64be

class Mp4FormatException(
    message: String,
) : Exception(message)

class BoxInfo(
    val offset: Long,
    val boxLength: Long,
    val headerLength: Int,
    val type: String,
    val userType: ByteArray?,
    val sizeFieldWasZero: Boolean,
)

object Mp4Codec {
    val ENGRAM_UUID = "ENGRAM-PHOTOS-01".encodeToByteArray()

    /** [header] is the first up-to-32 bytes at the box start. */
    fun parseHeader(
        header: ByteArray,
        offset: Long,
        fileRemaining: Long,
    ): BoxInfo {
        if (header.size < 8) throw Mp4FormatException("truncated box header at $offset")
        val size32 = header.u32be(0)
        val type = header.copyOfRange(4, 8).decodeToString()
        var headerLen = 8
        var boxLen = size32
        var wasZero = false
        if (size32 == 1L) {
            if (header.size < 16) throw Mp4FormatException("truncated largesize at $offset")
            boxLen = header.u64be(8)
            headerLen = 16
        } else if (size32 == 0L) {
            boxLen = fileRemaining
            wasZero = true
        }
        var userType: ByteArray? = null
        if (type == "uuid") {
            if (header.size < headerLen + 16) throw Mp4FormatException("truncated uuid box at $offset")
            userType = header.copyOfRange(headerLen, headerLen + 16)
            headerLen += 16
        }
        if (boxLen < headerLen) throw Mp4FormatException("bad box length at $offset")
        return BoxInfo(offset, boxLen, headerLen, type, userType, wasZero)
    }

    fun topLevel(bytes: ByteArray): List<BoxInfo> {
        val boxes = mutableListOf<BoxInfo>()
        var i = 0L
        while (i < bytes.size) {
            val headerEnd = minOf(bytes.size.toLong(), i + 32).toInt()
            val info = parseHeader(bytes.copyOfRange(i.toInt(), headerEnd), i, bytes.size - i)
            if (i + info.boxLength > bytes.size) throw Mp4FormatException("box overruns file at $i")
            boxes += info
            i += info.boxLength
        }
        return boxes
    }

    fun buildEngramBox(recordBytes: ByteArray): ByteArray {
        val len = 8L + 16 + recordBytes.size
        require(len <= 0xFFFFFFFFL) { "engram box too large" }
        val b = ByteArrayBuilder(len.toInt())
        b.appendU32be(len)
        b.append("uuid".encodeToByteArray())
        b.append(ENGRAM_UUID)
        b.append(recordBytes)
        return b.toByteArray()
    }

    fun isEngramBox(info: BoxInfo): Boolean = info.type == "uuid" && info.userType?.contentEquals(ENGRAM_UUID) == true

    /** In-memory embed for modest files; large videos go through jvm Mp4Files. */
    fun embed(
        bytes: ByteArray,
        newRecords: List<EngramRecord>,
    ): ByteArray {
        require(newRecords.isNotEmpty()) { "nothing to embed" }
        val boxes = topLevel(bytes)
        boxes.forEachIndexed { idx, b ->
            if (b.sizeFieldWasZero && idx != boxes.lastIndex) {
                throw Mp4FormatException("size-zero box before end of file not supported")
            }
        }
        val old = ByteArrayBuilder()
        val keep = ByteArrayBuilder()
        for (b in boxes) {
            val start = b.offset.toInt()
            val end = (b.offset + b.boxLength).toInt()
            when {
                isEngramBox(b) -> old.append(bytes, start + b.headerLength, end)
                b.sizeFieldWasZero -> {
                    // materialize an explicit size so appending after it stays legal
                    if (b.boxLength > 0xFFFFFFFFL) throw Mp4FormatException("size-zero box too large to materialize")
                    val patched = bytes.copyOfRange(start, end)
                    patched[0] = (b.boxLength ushr 24).toByte()
                    patched[1] = (b.boxLength ushr 16).toByte()
                    patched[2] = (b.boxLength ushr 8).toByte()
                    patched[3] = b.boxLength.toByte()
                    keep.append(patched)
                }
                else -> keep.append(bytes, start, end)
            }
        }
        keep.append(buildEngramBox(old.toByteArray() + RecordStream.encode(newRecords)))
        return keep.toByteArray()
    }

    fun readRecords(bytes: ByteArray): List<RecordHit> {
        val engram = topLevel(bytes).lastOrNull { isEngramBox(it) } ?: return emptyList()
        val from = (engram.offset + engram.headerLength).toInt()
        val until = (engram.offset + engram.boxLength).toInt()
        return RecordStream.decodeSequence(bytes, from, until)
    }
}
