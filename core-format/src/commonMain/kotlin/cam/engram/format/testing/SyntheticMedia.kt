package cam.engram.format.testing

import cam.engram.format.ByteArrayBuilder
import cam.engram.format.Crc32
import cam.engram.format.jpeg.XMP_APP1_HEADER
import cam.engram.format.png.PngChunk
import cam.engram.format.png.PngCodec
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind

/**
 * Tiny handcrafted files: structurally valid for parsers, not meant for image
 * decoders. Real Pixel/OEM corpus files live in lab/corpus (never committed).
 */
@Suppress("TooManyFunctions") // fixture catalog: one small builder per file shape
object SyntheticMedia {
    /**
     * A CRC-valid record frame with an unknown/future kind byte: readers must
     * surface it (crcOk, no high-level record) and rewriters must preserve it
     * (spec: unknown kinds preserved). Encodes a Note, then patches the kind byte
     * and repairs the trailing CRC so the frame stays wire-valid.
     */
    fun unknownKindFrame(kindCode: Int = 7): ByteArray = notePatchedAt(5, kindCode) // kind byte at offset 5

    /**
     * A CRC-valid record frame with a future wire-version byte: readers must
     * surface it opaque (crcOk, no typed record) and rewriters must preserve it
     * byte-exact (spec sec 10, frozen envelope).
     */
    fun unknownVersionFrame(version: Int = 2): ByteArray = notePatchedAt(4, version) // version byte at offset 4

    /**
     * A current-version note frame whose payload length field claims [spanBeyond]
     * extra bytes past the frame's real end, CRC left stale: a reader that grants
     * a CRC-bad frame's claimed span authority would swallow whatever follows.
     */
    fun frameWithInflatedLength(spanBeyond: Int): ByteArray {
        val frame = EngramRecord(RecordKind.Note, 1L, "corrupt".encodeToByteArray()).encode().copyOf()
        val payloadLenAt = EngramRecord.HEADER_LEN - 4 // empty writer: payloadLen right after the fixed header
        val claimed = frame.size + spanBeyond - payloadLenAt - 8
        frame[payloadLenAt] = (claimed ushr 24 and 0xFF).toByte()
        frame[payloadLenAt + 1] = (claimed ushr 16 and 0xFF).toByte()
        frame[payloadLenAt + 2] = (claimed ushr 8 and 0xFF).toByte()
        frame[payloadLenAt + 3] = (claimed and 0xFF).toByte()
        return frame
    }

    /**
     * A note frame whose first magic byte is damaged: decodeAt returns null at its
     * offset (the CRC is stale by construction, but decode never gets that far).
     * A sequence reader must not let it hide the frames stored behind it.
     */
    fun frameWithDamagedMagic(): ByteArray =
        EngramRecord(RecordKind.Note, 1L, "damaged".encodeToByteArray()).encode().also {
            it[0] = 'X'.code.toByte()
        }

    /**
     * A CRC-valid frame whose writer bytes are not valid UTF-8 and are long enough that
     * decoding them to replacement characters and re-encoding overshoots the 255-byte
     * writer limit. A decoder that eagerly builds the typed record throws on it, and
     * because the CRC is recomputed to be valid, tolerating the throw cannot be reduced
     * to rejecting CRC-bad frames: the carver must surface this frame opaque and keep
     * scanning (finding: a malformed writer aborted every later record).
     */
    fun frameWithUndecodableWriter(writerBytes: Int = 90): ByteArray {
        val body = ByteArrayBuilder()
        body.append(EngramRecord.MAGIC)
        body.append(EngramRecord.WIRE_VERSION)
        body.append(RecordKind.Note.code)
        body.appendU16be(0)
        body.append(ByteArray(EngramRecord.ID_LENGTH)) // id
        body.appendU64be(1L) // ts
        body.append(writerBytes) // writerLen
        body.append(ByteArray(writerBytes) { 0xFF.toByte() }) // not valid UTF-8
        body.appendU32be(0) // empty payload
        val bytes = body.toByteArray()
        return ByteArrayBuilder(bytes.size + 4).append(bytes).appendU32be(Crc32.of(bytes)).toByteArray()
    }

    // encode a Note, patch one header byte, recompute the trailing CRC so the frame stays wire-valid
    private fun notePatchedAt(
        offset: Int,
        value: Int,
    ): ByteArray {
        val frame = EngramRecord(RecordKind.Note, 1L, "future".encodeToByteArray()).encode().copyOf()
        frame[offset] = value.toByte()
        val bodyLen = frame.size - 4
        val crc = Crc32.of(frame, 0, bodyLen)
        frame[bodyLen] = (crc ushr 24 and 0xFF).toByte()
        frame[bodyLen + 1] = (crc ushr 16 and 0xFF).toByte()
        frame[bodyLen + 2] = (crc ushr 8 and 0xFF).toByte()
        frame[bodyLen + 3] = (crc and 0xFF).toByte()
        return frame
    }

    fun jpegPlain(entropyByte: Int = 0x12): ByteArray {
        val b = ByteArrayBuilder()
        soi(b)
        segment(b, 0xE0, jfifPayload())
        segment(b, 0xDB, ByteArray(65) { if (it == 0) 0 else 1 })
        segment(b, 0xC0, byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0))
        sosWithEntropy(b, entropyByte)
        eoi(b)
        return b.toByteArray()
    }

    fun jpegWithXmp(packet: String): ByteArray {
        val b = ByteArrayBuilder()
        soi(b)
        segment(b, 0xE0, jfifPayload())
        segment(b, 0xE1, XMP_APP1_HEADER + packet.encodeToByteArray())
        segment(b, 0xDB, ByteArray(65) { if (it == 0) 0 else 1 })
        segment(b, 0xC0, byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0))
        sosWithEntropy(b, 0x12)
        eoi(b)
        return b.toByteArray()
    }

    /** Multi-scan file: SOS, entropy, DHT, second SOS, entropy (progressive shape). */
    fun jpegProgressive(): ByteArray {
        val b = ByteArrayBuilder()
        soi(b)
        segment(b, 0xE0, jfifPayload())
        segment(b, 0xDB, ByteArray(65) { if (it == 0) 0 else 1 })
        segment(b, 0xC2, byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0))
        sosWithEntropy(b, 0x21)
        segment(b, 0xC4, byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5))
        sosWithEntropy(b, 0x22)
        eoi(b)
        return b.toByteArray()
    }

    /** DNL segment after the scan, before EOI. */
    fun jpegWithDnl(): ByteArray {
        val b = ByteArrayBuilder()
        soi(b)
        segment(b, 0xE0, jfifPayload())
        segment(b, 0xDB, ByteArray(65) { if (it == 0) 0 else 1 })
        segment(b, 0xC0, byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0))
        sosWithEntropy(b, 0x23)
        segment(b, 0xDC, byteArrayOf(0, 1))
        eoi(b)
        return b.toByteArray()
    }

    /** Extra 0xFF fill bytes between segments and before EOI. */
    fun jpegWithFillBytes(): ByteArray {
        val b = ByteArrayBuilder()
        soi(b)
        segment(b, 0xE0, jfifPayload())
        b.append(0xFF).append(0xFF)
        segment(b, 0xDB, ByteArray(65) { if (it == 0) 0 else 1 })
        segment(b, 0xC0, byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0))
        sosWithEntropy(b, 0x24)
        b.append(0xFF).append(0xFF).append(0xFF)
        eoi(b)
        return b.toByteArray()
    }

    /**
     * Primary jpeg with an MPF APP2 pointing at an appended secondary jpeg,
     * shaped like Ultra HDR primary + gain map. Optional [xmpAfterMpf] places
     * an XMP APP1 in the forbidden position after the MPF segment.
     */
    fun jpegWithMpfSecondary(xmpAfterMpf: String? = null): ByteArray {
        val secondary = jpegPlain(entropyByte = 0x21)
        val b = ByteArrayBuilder()
        soi(b)
        segment(b, 0xE0, jfifPayload())
        val mpfPayloadPos = b.size + 4
        segment(b, 0xE2, mpfPayload())
        xmpAfterMpf?.let { segment(b, 0xE1, XMP_APP1_HEADER + it.encodeToByteArray()) }
        segment(b, 0xDB, ByteArray(65) { if (it == 0) 0 else 1 })
        segment(b, 0xC0, byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0))
        sosWithEntropy(b, 0x12)
        eoi(b)
        val primary = b.toByteArray()
        val file = primary + secondary
        val tiffBase = mpfPayloadPos + 4
        patchU32le(file, tiffBase + ENTRIES_REL + 4, primary.size.toLong())
        patchU32le(file, tiffBase + ENTRIES_REL + 16 + 4, secondary.size.toLong())
        patchU32le(file, tiffBase + ENTRIES_REL + 16 + 8, (primary.size - tiffBase).toLong())
        return file
    }

    fun png1x1(): ByteArray {
        val chunks =
            listOf(
                PngChunk("IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0)),
                PngChunk(
                    "IDAT",
                    byteArrayOf(
                        0x78,
                        0x01,
                        0x01,
                        0x02,
                        0x00,
                        0xFD.toByte(),
                        0xFF.toByte(),
                        0x00,
                        0x00,
                        0x00,
                        0x02,
                        0x00,
                        0x01,
                    ),
                ),
                PngChunk("IEND", ByteArray(0)),
            )
        val b = ByteArrayBuilder()
        b.append(PngCodec.SIGNATURE)
        chunks.forEach { b.append(it.encode()) }
        return b.toByteArray()
    }

    fun mp4Minimal(withZeroSizeLastBox: Boolean = false): ByteArray {
        val b = ByteArrayBuilder()
        b.appendU32be(20).append("ftyp".encodeToByteArray())
        b.append("isom".encodeToByteArray()).appendU32be(0).append("isom".encodeToByteArray())
        b.appendU32be(12).append("free".encodeToByteArray()).append("labz".encodeToByteArray())
        b.appendU32be(if (withZeroSizeLastBox) 0 else 16).append("mdat".encodeToByteArray())
        b.append(ByteArray(8) { 0x5A })
        return b.toByteArray()
    }

    /** mdat carried in a largesize (64-bit) box. */
    fun mp4WithLargesizeMdat(): ByteArray {
        val b = ByteArrayBuilder()
        b.appendU32be(20).append("ftyp".encodeToByteArray())
        b.append("isom".encodeToByteArray()).appendU32be(0).append("isom".encodeToByteArray())
        b.appendU32be(1).append("mdat".encodeToByteArray()).appendU64be(16 + 8L)
        b.append(ByteArray(8) { 0x5A })
        return b.toByteArray()
    }

    /** Camera-like layout: mdat first, moov last (caption-writable). */
    fun mp4MoovLast(): ByteArray {
        val b = ByteArrayBuilder()
        b.appendU32be(20).append("ftyp".encodeToByteArray())
        b.append("isom".encodeToByteArray()).appendU32be(0).append("isom".encodeToByteArray())
        b.appendU32be(16).append("mdat".encodeToByteArray()).append(ByteArray(8) { 0x5A })
        val mvhd = ByteArray(12) { 0x01 }
        val moovBody = ByteArrayBuilder()
        moovBody.appendU32be(8L + mvhd.size).append("mvhd".encodeToByteArray()).append(mvhd)
        val moov = moovBody.toByteArray()
        b.appendU32be(8L + moov.size).append("moov".encodeToByteArray()).append(moov)
        return b.toByteArray()
    }

    private const val ENTRIES_REL = 50

    private fun soi(b: ByteArrayBuilder) {
        b.append(0xFF).append(0xD8)
    }

    private fun eoi(b: ByteArrayBuilder) {
        b.append(0xFF).append(0xD9)
    }

    private fun sosWithEntropy(
        b: ByteArrayBuilder,
        entropyByte: Int,
    ) {
        segment(b, 0xDA, byteArrayOf(1, 1, 0, 0, 63, 0))
        b
            .append(0x00)
            .append(entropyByte)
            .append(0xFF)
            .append(0x00)
            .append(0x37)
    }

    private fun jfifPayload(): ByteArray {
        val b = ByteArrayBuilder()
        b.append("JFIF".encodeToByteArray()).append(0)
        b.append(1).append(1)
        b.append(0)
        b.appendU16be(1)
        b.appendU16be(1)
        b.append(0).append(0)
        return b.toByteArray()
    }

    private fun segment(
        b: ByteArrayBuilder,
        marker: Int,
        payload: ByteArray,
    ) {
        require(payload.size + 2 <= 0xFFFF) { "fixture segment payload too large: ${payload.size}" }
        b.append(0xFF).append(marker)
        b.appendU16be(payload.size + 2)
        b.append(payload)
    }

    private fun mpfPayload(): ByteArray {
        val b = ByteArrayBuilder()
        b.append("MPF".encodeToByteArray()).append(0)
        b
            .append(0x49)
            .append(0x49)
            .append(0x2A)
            .append(0x00)
        b
            .append(0x08)
            .append(0x00)
            .append(0x00)
            .append(0x00)
        b.append(0x03).append(0x00)
        ifdEntryLe(b, 0xB000, 7, 4, 0x30303130) // ascii "0100"
        ifdEntryLe(b, 0xB001, 4, 1, 2)
        ifdEntryLe(b, 0xB002, 7, 32, ENTRIES_REL.toLong())
        b
            .append(0)
            .append(0)
            .append(0)
            .append(0)
        repeat(32) { b.append(0) } // two MP entries, patched by the caller
        return b.toByteArray()
    }

    private fun ifdEntryLe(
        b: ByteArrayBuilder,
        tag: Int,
        type: Int,
        count: Long,
        value: Long,
    ) {
        b.append(tag).append(tag ushr 8)
        b.append(type).append(type ushr 8)
        appendU32le(b, count)
        appendU32le(b, value)
    }

    private fun appendU32le(
        b: ByteArrayBuilder,
        v: Long,
    ) {
        b
            .append(v.toInt())
            .append((v ushr 8).toInt())
            .append((v ushr 16).toInt())
            .append((v ushr 24).toInt())
    }

    private fun patchU32le(
        bytes: ByteArray,
        at: Int,
        v: Long,
    ) {
        bytes[at] = v.toByte()
        bytes[at + 1] = (v ushr 8).toByte()
        bytes[at + 2] = (v ushr 16).toByte()
        bytes[at + 3] = (v ushr 24).toByte()
    }
}
