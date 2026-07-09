package cam.engram.app.data.scan

import cam.engram.app.data.media.ContentAccess
import cam.engram.format.ByteArrayBuilder
import cam.engram.format.mp4.Mp4Channels
import cam.engram.format.png.PngCodec
import cam.engram.format.read.Memory
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordStream

class ScanOutcome(
    val recordCount: Int,
    val payloadLength: Long,
    val recordsBlob: ByteArray?,
    val searchableText: String,
)

/** Reads a media file and reports the engram records it carries. */
class RecordScanner(
    private val access: ContentAccess,
) {
    fun scan(
        uri: String,
        isVideo: Boolean,
        mime: String,
    ): ScanOutcome? = rawFrames(uri, isVideo, mime)?.let { outcome(it) }

    /**
     * The idHexes of the CRC-valid records currently in the target. Write-back
     * recovery uses this to tell a completed write (every expected id present)
     * from an interrupted one (records missing), instead of merely re-parsing
     * the container.
     */
    fun presentIds(
        uri: String,
        isVideo: Boolean,
        mime: String,
    ): Set<String> =
        (rawFrames(uri, isVideo, mime) ?: emptyList())
            .mapNotNull { EngramRecord.decodeAt(it, 0)?.record?.idHex }
            .toSet()

    /**
     * The raw bytes of every CRC-valid record frame in the target, in file order.
     * Keeping the raw frame bytes (rather than re-encoding decoded records) preserves
     * unknown/future kinds, which a rewriter must not silently drop (spec: unknown
     * kinds preserved). null only when the target could not be read at all.
     */
    private fun rawFrames(
        uri: String,
        isVideo: Boolean,
        mime: String,
    ): List<ByteArray>? = if (isVideo) videoFrames(uri) else photoFrames(uri, mime)

    private fun photoFrames(
        uri: String,
        mime: String,
    ): List<ByteArray>? {
        val bytes = access.readBytes(uri) ?: return null
        return if (mime == "image/png") {
            runCatching { PngCodec.engramFrames(PngCodec.parse(bytes)) }.getOrElse { emptyList() }
        } else {
            // carve scan works for jpeg and any unknown image container
            RecordStream
                .scan(bytes)
                .filter { it.decoded.crcOk }
                .map { bytes.copyOfRange(it.offset, it.offset + it.decoded.byteLength) }
        }
    }

    private fun videoFrames(uri: String): List<ByteArray>? =
        access.withChannel(uri) { ch ->
            runCatching { Mp4Channels.readRawFrames(ch) }.getOrElse { emptyList() }
        }

    private fun outcome(frames: List<ByteArray>): ScanOutcome {
        if (frames.isEmpty()) return ScanOutcome(0, 0, null, "")
        val blob = ByteArrayBuilder()
        var payload = 0L
        frames.forEach {
            blob.append(it)
            payload += it.size
        }
        val text = Memory.fromRecords(frames.mapNotNull { EngramRecord.decodeAt(it, 0)?.record }).searchableText()
        return ScanOutcome(frames.size, payload, blob.toByteArray(), text)
    }
}
