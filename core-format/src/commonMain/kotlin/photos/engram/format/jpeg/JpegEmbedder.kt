package photos.engram.format.jpeg

import photos.engram.format.records.EngramRecord
import photos.engram.format.records.RecordStream
import photos.engram.format.xmp.XmpEngine
import photos.engram.format.xmp.XmpUpdate

class JpegEmbedder(private val xmp: XmpEngine) {
    fun embed(
        source: ByteArray,
        newRecords: List<EngramRecord>,
        mirrorDescription: String?,
    ): ByteArray {
        require(newRecords.isNotEmpty()) { "nothing to embed" }
        val parts = JpegCodec.parse(source).toMutableList()
        val existing =
            parts.filterIsInstance<TrailerData>()
                .flatMap { RecordStream.scan(it.raw) }
                .filter { it.decoded.crcOk }
        val added = RecordStream.encode(newRecords)
        val update =
            XmpUpdate(
                mirrorDescription = mirrorDescription,
                payloadLength = existing.sumOf { it.decoded.byteLength.toLong() } + added.size,
                recordCount = existing.size + newRecords.size,
            )
        val xmpIdx = parts.indexOfFirst { it is Segment && it.isXmpApp1() }
        val existingPacket = (parts.getOrNull(xmpIdx) as? Segment)?.xmpPacket()
        val packet = xmp.apply(existingPacket, update)
        val xmpPayload = XMP_APP1_HEADER + packet.encodeToByteArray()
        if (xmpPayload.size > Segment.MAX_PAYLOAD) {
            throw JpegFormatException("xmp packet needs ExtendedXMP, not implemented in v0 (size ${xmpPayload.size})")
        }
        val seg = Segment.of(JpegCodec.APP1, xmpPayload)
        if (xmpIdx >= 0) {
            parts[xmpIdx] = seg
        } else {
            // stay inside the leading APP0/APP1 run: growth before the MPF APP2
            // shifts the MPF header and the trailing images by the same delta,
            // so the relative offsets MPF stores remain correct
            var at = 1
            while (at < parts.size) {
                val p = parts[at]
                if (p is Segment && (p.marker == JpegCodec.APP0 || p.marker == JpegCodec.APP1)) at++ else break
            }
            parts.add(at, seg)
        }
        parts.add(TrailerData(added))
        val out = JpegCodec.serialize(parts)
        val before = MpfInspector.inspect(source)
        val after = MpfInspector.inspect(out)
        if (before.valid && !after.valid) {
            throw JpegFormatException("writer broke MPF offsets: ${after.problems}")
        }
        return out
    }
}
