package cam.engram.format.jpeg

import cam.engram.format.ByteArrayBuilder
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordStream
import cam.engram.format.setU32
import cam.engram.format.xmp.XmpEngine
import cam.engram.format.xmp.XmpUpdate

class JpegEmbedder(
    private val xmp: XmpEngine,
) {
    fun embed(
        source: ByteArray,
        newRecords: List<EngramRecord>,
        mirrorDescription: String?,
        carryFrames: List<ByteArray> = emptyList(),
    ): ByteArray {
        require(newRecords.isNotEmpty() || carryFrames.isNotEmpty()) { "nothing to embed" }
        val parts = JpegCodec.parse(source).toMutableList()
        val xmpIdxBefore = parts.indexOfFirst { it is Segment && it.isXmpApp1() }
        val mpfIdxBefore = parts.indexOfFirst { it is Segment && it.isMpfApp2() }
        val existingStandard = (parts.getOrNull(xmpIdxBefore) as? Segment)?.xmpPacket()
        guardMotionPhoto(existingStandard)
        guardSegmentOrder(parts, xmpIdxBefore, mpfIdxBefore)
        val existingExtended = ExtendedXmp.collect(parts)

        val existing =
            parts
                .filterIsInstance<TrailerData>()
                .flatMap { RecordStream.scan(it.raw) }
                .filter { it.decoded.crcOk }
        // carryFrames are already-encoded frames (e.g. unknown kinds from the cache)
        // appended verbatim so a re-embed preserves them (spec: unknown kinds preserved)
        val addedBuilder = ByteArrayBuilder()
        addedBuilder.append(RecordStream.encode(newRecords))
        carryFrames.forEach { addedBuilder.append(it) }
        val added = addedBuilder.toByteArray()
        val update =
            XmpUpdate(
                mirrorDescription = mirrorDescription,
                payloadLength = existing.sumOf { it.decoded.byteLength.toLong() } + added.size,
                recordCount = existing.size + newRecords.size + carryFrames.size,
            )
        val result =
            xmp.apply(
                existingStandard = existingStandard,
                existingExtended = existingExtended?.packet,
                update = update,
                standardLimitBytes = Segment.MAX_PAYLOAD - XMP_APP1_HEADER.size,
            )
        val stdPayload = XMP_APP1_HEADER + result.standardPacket.encodeToByteArray()
        if (stdPayload.size > Segment.MAX_PAYLOAD) {
            throw JpegFormatException("standard xmp packet exceeds a single APP1 segment after split")
        }
        val stdSeg = Segment.of(JpegCodec.APP1, stdPayload)
        val extSegs = result.extendedPacket?.let { ExtendedXmp.buildSegments(it) }.orEmpty()

        parts.removeAll { it is Segment && it.isExtendedXmpApp1() }
        var stdIdx = parts.indexOfFirst { it is Segment && it.isXmpApp1() }
        if (stdIdx >= 0) {
            parts[stdIdx] = stdSeg
        } else {
            // stay inside the leading APP0/APP1 run: growth before the MPF APP2
            // shifts the MPF header and the trailing images by the same delta,
            // so the relative offsets MPF stores remain correct
            stdIdx = 1
            while (stdIdx < parts.size) {
                val p = parts[stdIdx]
                if (p is Segment && (p.marker == JpegCodec.APP0 || p.marker == JpegCodec.APP1)) stdIdx++ else break
            }
            parts.add(stdIdx, stdSeg)
        }
        parts.addAll(stdIdx + 1, extSegs)

        if (mirrorDescription != null) upsertIptc(parts, stdIdx + 1 + extSegs.size, mirrorDescription)

        parts.add(TrailerData(added))
        val out = JpegCodec.serialize(parts)
        val before = MpfInspector.inspect(source)
        // the inserted XMP/IPTC grows the primary image, so patch the MPF primary MP entry size
        // to the new SOI..EOI span; otherwise the emitted MPF is inconsistent (finding 3)
        val probe = MpfInspector.inspect(out)
        probe.primarySizeFieldPos?.let { pos ->
            val eoi = parts.indexOfFirst { it is MarkerOnly && it.marker == JpegCodec.EOI }
            if (eoi >= 0) {
                out.setU32(pos.toInt(), parts.take(eoi + 1).sumOf { it.raw.size.toLong() }, probe.little)
            }
        }
        val after = MpfInspector.inspect(out)
        if (before.valid && !after.valid) {
            throw JpegFormatException("writer broke MPF offsets: ${after.problems}")
        }
        return out
    }

    private fun guardMotionPhoto(existingStandard: String?) {
        if (existingStandard == null) return
        if (existingStandard.contains("MotionPhoto") || existingStandard.contains("MicroVideo")) {
            throw JpegFormatException(
                "motion photo detected: trailer coexistence rules are unverified (plan 0.5 landmine 2), refusing to write",
            )
        }
    }

    private fun guardSegmentOrder(
        parts: List<JpegPart>,
        xmpIdx: Int,
        mpfIdx: Int,
    ) {
        if (mpfIdx < 0) return
        if (xmpIdx > mpfIdx) {
            throw JpegFormatException(
                "xmp segment sits after MPF: rewriting it would shift MPF-referenced images, refusing to write",
            )
        }
        parts.forEachIndexed { i, p ->
            if (i <= mpfIdx || p !is Segment) return@forEachIndexed
            if (p.isExtendedXmpApp1() || Iptc.isIptcApp13(p)) {
                throw JpegFormatException(
                    "metadata segment after MPF: rewriting it would shift MPF-referenced images, refusing to write",
                )
            }
        }
    }

    private fun upsertIptc(
        parts: MutableList<JpegPart>,
        insertAt: Int,
        caption: String,
    ) {
        val idx = parts.indexOfFirst { it is Segment && Iptc.isIptcApp13(it) }
        val payload = Iptc.upsertCaption((parts.getOrNull(idx) as? Segment)?.payload, caption)
        if (payload.size > Segment.MAX_PAYLOAD) {
            throw JpegFormatException("APP13 payload too large after caption upsert")
        }
        val seg = Segment.of(Iptc.APP13_MARKER, payload)
        if (idx >= 0) parts[idx] = seg else parts.add(insertAt, seg)
    }
}
