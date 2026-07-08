package cam.engram.format.mp4

import cam.engram.format.records.RecordHit
import cam.engram.format.records.RecordStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * Seekable-channel twin of [Mp4Files] for callers that only have a file
 * descriptor (Android MediaStore): walks headers and reads our tail box
 * without pulling mdat into memory.
 */
object Mp4Channels {
    fun topLevel(ch: SeekableByteChannel): List<BoxInfo> {
        val len = ch.size()
        val boxes = mutableListOf<BoxInfo>()
        var i = 0L
        while (i < len) {
            val want = minOf(32L, len - i).toInt()
            val buf = ByteBuffer.allocate(want)
            ch.position(i)
            while (buf.hasRemaining() && ch.read(buf) > 0) {
                // keep filling: a short read is not end of file
            }
            if (buf.position() < want) throw Mp4FormatException("unexpected end of file at $i")
            val info = Mp4Codec.parseHeader(buf.array(), i, len - i)
            if (i + info.boxLength > len) throw Mp4FormatException("box overruns file at $i")
            boxes += info
            i += info.boxLength
        }
        return boxes
    }

    // a hostile/corrupt uuid box must not OOM the reader (review F15); real
    // engram payloads are bounded by the ~10MB product soft cap, this is slack
    const val MAX_RECORD_BOX_BYTES = 128L * 1024 * 1024

    fun readRecords(ch: SeekableByteChannel): List<RecordHit> {
        val engram = topLevel(ch).lastOrNull { Mp4Codec.isEngramBox(it) } ?: return emptyList()
        val payloadLen = engram.boxLength - engram.headerLength
        if (payloadLen > MAX_RECORD_BOX_BYTES) throw Mp4FormatException("engram box too large: $payloadLen")
        val payload = ByteBuffer.allocate(payloadLen.toInt())
        ch.position(engram.offset + engram.headerLength)
        while (payload.hasRemaining() && ch.read(payload) > 0) {
            // fill fully
        }
        if (payload.hasRemaining()) throw Mp4FormatException("engram box truncated")
        return RecordStream.decodeSequence(payload.array())
    }

    fun readMoovBox(ch: SeekableByteChannel): ByteArray? {
        val moov = topLevel(ch).lastOrNull { it.type == "moov" } ?: return null
        val buf = ByteBuffer.allocate(moov.boxLength.toInt())
        ch.position(moov.offset)
        while (buf.hasRemaining() && ch.read(buf) > 0) {
            // fill fully
        }
        return if (buf.hasRemaining()) null else buf.array()
    }
}
