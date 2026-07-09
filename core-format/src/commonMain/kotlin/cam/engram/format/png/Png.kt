package cam.engram.format.png

import cam.engram.format.ByteArrayBuilder
import cam.engram.format.Crc32
import cam.engram.format.records.DecodedRecord
import cam.engram.format.records.EngramRecord
import cam.engram.format.startsWith
import cam.engram.format.u32be
import cam.engram.format.xmp.XmpEngine
import cam.engram.format.xmp.XmpUpdate

class PngFormatException(
    message: String,
) : Exception(message)

class PngChunk(
    val type: String,
    val data: ByteArray,
    val crcOk: Boolean = true,
) {
    fun encode(): ByteArray {
        val t = type.encodeToByteArray()
        require(t.size == 4) { "chunk type must be 4 bytes" }
        val b = ByteArrayBuilder(12 + data.size)
        b.appendU32be(data.size.toLong())
        b.append(t)
        b.append(data)
        b.appendU32be(Crc32.of(t + data))
        return b.toByteArray()
    }
}

class PngFile(
    val chunks: List<PngChunk>,
    val trailer: ByteArray,
)

object PngCodec {
    val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    // ancillary, private, safe-to-copy: editors that follow the spec carry it along
    const val ENGRAM_CHUNK = "egRm"
    const val XMP_KEYWORD = "XML:com.adobe.xmp"

    fun parse(bytes: ByteArray): PngFile {
        if (!bytes.startsWith(SIGNATURE)) throw PngFormatException("not a png")
        val chunks = mutableListOf<PngChunk>()
        var i = 8
        while (i < bytes.size) {
            if (i + 12 > bytes.size) throw PngFormatException("truncated chunk at $i")
            val len = bytes.u32be(i)
            if (len > Int.MAX_VALUE.toLong() || i + 12 + len > bytes.size) {
                throw PngFormatException("bad chunk length at $i")
            }
            val dataEnd = (i + 8 + len).toInt()
            val type = bytes.copyOfRange(i + 4, i + 8).decodeToString()
            val data = bytes.copyOfRange(i + 8, dataEnd)
            val crcOk = bytes.u32be(dataEnd) == Crc32.of(bytes, i + 4, dataEnd)
            chunks += PngChunk(type, data, crcOk)
            i = dataEnd + 4
            if (type == "IEND") break
        }
        return PngFile(chunks, bytes.copyOfRange(i, bytes.size))
    }

    fun serialize(file: PngFile): ByteArray {
        val b = ByteArrayBuilder()
        b.append(SIGNATURE)
        file.chunks.forEach { b.append(it.encode()) }
        b.append(file.trailer)
        return b.toByteArray()
    }

    fun xmpChunk(packet: String): PngChunk {
        val b = ByteArrayBuilder()
        b.append(XMP_KEYWORD.encodeToByteArray())
        b.append(0)
        b.append(0) // compression flag: uncompressed
        b.append(0) // compression method
        b.append(0) // empty language tag
        b.append(0) // empty translated keyword
        b.append(packet.encodeToByteArray())
        return PngChunk("iTXt", b.toByteArray())
    }

    fun xmpPacket(chunk: PngChunk): String? {
        if (chunk.type != "iTXt") return null
        val kw = XMP_KEYWORD.encodeToByteArray()
        val d = chunk.data
        if (d.size < kw.size + 5 || !d.startsWith(kw) || d[kw.size] != 0.toByte()) return null
        var i = kw.size + 1
        if (d[i] != 0.toByte()) return null // compressed xmp is not something we write
        i += 2
        while (i < d.size && d[i] != 0.toByte()) i++
        i++
        while (i < d.size && d[i] != 0.toByte()) i++
        i++
        if (i > d.size) return null
        return d.copyOfRange(i, d.size).decodeToString()
    }

    // a record must span the chunk exactly: trailing bytes mean corruption
    fun engramRecords(file: PngFile): List<DecodedRecord> =
        file.chunks
            .filter { it.type == ENGRAM_CHUNK }
            .mapNotNull { c -> EngramRecord.decodeAt(c.data, 0)?.takeIf { it.byteLength == c.data.size } }

    // raw bytes of every CRC-valid engram frame, for callers that must preserve
    // unknown/future kinds verbatim (the DecodedRecord list cannot round-trip them)
    fun engramFrames(file: PngFile): List<ByteArray> =
        file.chunks
            .filter { it.type == ENGRAM_CHUNK }
            .filter { c -> EngramRecord.decodeAt(c.data, 0)?.let { it.crcOk && it.byteLength == c.data.size } == true }
            .map { it.data }

    fun engramChunkCount(file: PngFile): Int = file.chunks.count { it.type == ENGRAM_CHUNK }
}

class PngEmbedder(
    private val xmp: XmpEngine,
) {
    fun embed(
        source: ByteArray,
        newRecords: List<EngramRecord>,
        mirrorDescription: String?,
        carryFrames: List<ByteArray> = emptyList(),
    ): ByteArray {
        require(newRecords.isNotEmpty()) { "nothing to embed" }
        val file = PngCodec.parse(source)
        val chunks = file.chunks.toMutableList()
        val existing = PngCodec.engramRecords(file).filter { it.crcOk }
        // carryFrames are already-encoded frames (e.g. unknown kinds from the cache)
        // appended verbatim so a re-embed preserves them (spec: unknown kinds preserved)
        val newBytes = newRecords.map { it.encode() } + carryFrames
        val update =
            XmpUpdate(
                mirrorDescription = mirrorDescription,
                payloadLength = existing.sumOf { it.byteLength.toLong() } + newBytes.sumOf { it.size.toLong() },
                recordCount = existing.size + newBytes.size,
            )
        val xmpIdx = chunks.indexOfFirst { PngCodec.xmpPacket(it) != null }
        // iTXt has no segment size limit, so the packet never needs an ExtendedXMP split
        val result =
            xmp.apply(
                existingStandard = chunks.getOrNull(xmpIdx)?.let { PngCodec.xmpPacket(it) },
                existingExtended = null,
                update = update,
                standardLimitBytes = Int.MAX_VALUE,
            )
        val chunk = PngCodec.xmpChunk(result.standardPacket)
        if (xmpIdx >= 0) {
            chunks[xmpIdx] = chunk
        } else {
            val ihdr = chunks.indexOfFirst { it.type == "IHDR" }
            chunks.add(if (ihdr >= 0) ihdr + 1 else 0, chunk)
        }
        val iend = chunks.indexOfFirst { it.type == "IEND" }.let { if (it < 0) chunks.size else it }
        chunks.addAll(iend, newBytes.map { PngChunk(PngCodec.ENGRAM_CHUNK, it) })
        return PngCodec.serialize(PngFile(chunks, file.trailer))
    }
}
