package photos.engram.app.data.scan

import photos.engram.app.data.media.ContentAccess
import photos.engram.format.ByteArrayBuilder
import photos.engram.format.mp4.Mp4Channels
import photos.engram.format.png.PngCodec
import photos.engram.format.read.Memory
import photos.engram.format.records.DecodedRecord
import photos.engram.format.records.RecordStream

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
    ): ScanOutcome? = if (isVideo) scanVideo(uri) else scanPhoto(uri, mime)

    private fun scanPhoto(
        uri: String,
        mime: String,
    ): ScanOutcome? {
        val bytes = access.readBytes(uri) ?: return null
        val decoded: List<DecodedRecord> =
            if (mime == "image/png") {
                runCatching { PngCodec.engramRecords(PngCodec.parse(bytes)) }
                    .getOrElse { return outcome(emptyList()) }
            } else {
                // carve scan works for jpeg and any unknown image container
                RecordStream.scan(bytes).map { it.decoded }
            }
        return outcome(decoded)
    }

    private fun scanVideo(uri: String): ScanOutcome? =
        access.withChannel(uri) { ch ->
            outcome(runCatching { Mp4Channels.readRecords(ch).map { it.decoded } }.getOrElse { emptyList() })
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
