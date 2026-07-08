package photos.engram.app.domain

import photos.engram.app.data.db.MediaItemEntity
import photos.engram.app.data.media.ContentAccess
import photos.engram.format.mp4.Mp4Channels
import photos.engram.format.png.PngCodec
import photos.engram.format.read.Memory
import photos.engram.format.records.RecordHit
import photos.engram.format.records.RecordStream

/** Loads the current record log from a media file and interprets it as a Memory. */
class MemoryReader(
    private val access: ContentAccess,
) {
    fun read(item: MediaItemEntity): Memory {
        val hits =
            if (item.isVideo) {
                access.withChannel(item.uri) { Mp4Channels.readRecords(it) }.orEmpty()
            } else {
                val bytes = access.readBytes(item.uri) ?: return Memory.fromRecords(emptyList())
                if (item.mime == "image/png") {
                    runCatching {
                        PngCodec.engramRecords(PngCodec.parse(bytes)).mapIndexed { i, d -> RecordHit(i, d) }
                    }.getOrDefault(emptyList())
                } else {
                    RecordStream.scan(bytes)
                }
            }
        return Memory.from(hits.filter { it.decoded.crcOk })
    }
}
