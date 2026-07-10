package cam.engram.app.data.scan

import cam.engram.app.data.media.ContentAccess
import cam.engram.format.ByteArrayBuilder
import cam.engram.format.archive.EngramArchive
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
    // md5 content hash of the media for cache-orphan export (finding 9); empty for videos,
    // which stream rather than load whole, so scanning never pulls a whole video into memory
    val contentHash: String,
)

/** Reads a media file and reports the engram records it carries. */
class RecordScanner(
    private val access: ContentAccess,
) {
    fun scan(
        uri: String,
        isVideo: Boolean,
        mime: String,
    ): ScanOutcome? {
        val (frames, bytes) = readTarget(uri, isVideo, mime) ?: return null
        return outcome(frames, bytes?.let { EngramArchive.contentHashName(it) }.orEmpty())
    }

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
        (readTarget(uri, isVideo, mime)?.first ?: emptyList())
            .mapNotNull { EngramRecord.decodeAt(it, 0)?.record?.idHex }
            .toSet()

    /**
     * Reads the target once and returns the raw bytes of every CRC-valid record frame (in file
     * order) plus, for images, the whole media bytes so a caller can content-hash without a
     * second read. Videos stream, so their bytes are null and they are not hashed here. Keeping
     * raw frame bytes (rather than re-encoding decoded records) preserves unknown/future kinds a
     * rewriter must not silently drop. null only when the target could not be read at all.
     */
    private fun readTarget(
        uri: String,
        isVideo: Boolean,
        mime: String,
    ): Pair<List<ByteArray>, ByteArray?>? {
        if (isVideo) {
            val frames = videoFrames(uri) ?: return null
            return frames to null
        }
        val bytes = access.readBytes(uri) ?: return null
        return carvePhoto(bytes, mime) to bytes
    }

    private fun carvePhoto(
        bytes: ByteArray,
        mime: String,
    ): List<ByteArray> =
        if (mime == "image/png") {
            runCatching { PngCodec.engramFrames(PngCodec.parse(bytes)) }.getOrElse { emptyList() }
        } else {
            // carve scan works for jpeg and any unknown image container
            RecordStream
                .scan(bytes)
                .filter { it.decoded.crcOk }
                .map { bytes.copyOfRange(it.offset, it.offset + it.decoded.byteLength) }
        }

    private fun videoFrames(uri: String): List<ByteArray>? =
        access.withChannel(uri) { ch ->
            runCatching { Mp4Channels.readRawFrames(ch) }.getOrElse { emptyList() }
        }

    private fun outcome(
        frames: List<ByteArray>,
        contentHash: String,
    ): ScanOutcome {
        if (frames.isEmpty()) return ScanOutcome(0, 0, null, "", contentHash)
        val blob = ByteArrayBuilder()
        var payload = 0L
        frames.forEach {
            blob.append(it)
            payload += it.size
        }
        val text = Memory.fromRecords(frames.mapNotNull { EngramRecord.decodeAt(it, 0)?.record }).searchableText()
        return ScanOutcome(frames.size, payload, blob.toByteArray(), text, contentHash)
    }
}
