package cam.engram.app.data.scan

import cam.engram.app.data.media.ContentAccess
import cam.engram.format.ByteArrayBuilder
import cam.engram.format.mp4.Mp4Channels
import cam.engram.format.png.PngCodec
import cam.engram.format.read.Memory
import cam.engram.format.records.DecodedRecord
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
    ): ScanOutcome? = decodedRecords(uri, isVideo, mime)?.let { outcome(it) }

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
        (decodedRecords(uri, isVideo, mime) ?: emptyList())
            .filter { it.crcOk && it.record != null }
            .map { it.record!!.idHex }
            .toSet()

    private fun decodedRecords(
        uri: String,
        isVideo: Boolean,
        mime: String,
    ): List<DecodedRecord>? = if (isVideo) videoRecords(uri) else photoRecords(uri, mime)

    private fun photoRecords(
        uri: String,
        mime: String,
    ): List<DecodedRecord>? {
        val bytes = access.readBytes(uri) ?: return null
        return if (mime == "image/png") {
            runCatching { PngCodec.engramRecords(PngCodec.parse(bytes)) }.getOrElse { emptyList() }
        } else {
            // carve scan works for jpeg and any unknown image container
            RecordStream.scan(bytes).map { it.decoded }
        }
    }

    private fun videoRecords(uri: String): List<DecodedRecord>? =
        access.withChannel(uri) { ch ->
            runCatching { Mp4Channels.readRecords(ch).map { it.decoded } }.getOrElse { emptyList() }
        }

    private fun outcome(decoded: List<DecodedRecord>): ScanOutcome {
        val valid = decoded.filter { it.crcOk && it.record != null }
        if (valid.isEmpty()) return ScanOutcome(0, 0, null, "")
        val blob = ByteArrayBuilder()
        var payload = 0L
        valid.forEach {
            val encoded = it.record!!.encode()
            blob.append(encoded)
            payload += encoded.size
        }
        val text = Memory.fromRecords(valid.mapNotNull { it.record }).searchableText()
        return ScanOutcome(valid.size, payload, blob.toByteArray(), text)
    }
}
