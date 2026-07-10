package cam.engram.format.records

/**
 * Byte-exact helpers over a concatenated record-frame log (the strip-recovery
 * cache format). Frames key by id plus trailing crc, so identical frames dedup
 * and unknown kinds or versions participate without being decoded.
 */
object FrameLog {
    /** Raw bytes of every CRC-valid frame in [blob], in log order. */
    fun crcOkFrames(blob: ByteArray): List<ByteArray> =
        RecordStream
            .decodeSequence(blob)
            .filter { it.decoded.crcOk }
            .map { blob.copyOfRange(it.offset, it.offset + it.decoded.byteLength) }

    /**
     * [scanned] plus every CRC-valid frame of [cached] the scan no longer
     * carries, in cache order, so records lost from the file survive in the
     * cache for strip repair.
     */
    fun mergeSuperset(
        scanned: ByteArray,
        scannedCount: Int,
        cached: ByteArray,
    ): Pair<ByteArray, Int> {
        val scannedKeys = crcOkFrames(scanned).map { it.frameKey() }.toSet()
        val missing = crcOkFrames(cached).filter { it.frameKey() !in scannedKeys }
        if (missing.isEmpty()) return scanned to scannedCount
        val merged = missing.fold(scanned) { acc, frame -> acc + frame }
        return merged to (scannedCount + missing.size)
    }

    // the 16-byte id lives at frame offset 8 and the crc in the last 4 bytes (frozen
    // envelope, spec sec 10); the pair keys a frame without decoding it
    fun ByteArray.frameKey(): List<Byte> = (copyOfRange(8, 24) + copyOfRange(size - 4, size)).toList()
}
