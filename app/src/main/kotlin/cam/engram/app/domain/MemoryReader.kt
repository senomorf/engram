package cam.engram.app.domain

import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.media.ContentAccess
import cam.engram.format.mp4.Mp4Channels
import cam.engram.format.png.PngCodec
import cam.engram.format.read.Memory
import cam.engram.format.records.RecordHit
import cam.engram.format.records.RecordStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Loads the current record log from a media file and interprets it as a Memory. */
class MemoryReader(
    private val access: ContentAccess,
    private val io: CoroutineDispatcher,
) {
    // reads and parses the whole file, so it runs off the main thread (review F1)
    suspend fun read(item: MediaItemEntity): Memory =
        withContext(io) {
            val hits =
                if (item.isVideo) {
                    access.withChannel(item.uri) { Mp4Channels.readRecords(it) }.orEmpty()
                } else {
                    val bytes = access.readBytes(item.uri) ?: return@withContext Memory.fromRecords(emptyList())
                    if (item.mime == "image/png") {
                        runCatching {
                            PngCodec.engramRecords(PngCodec.parse(bytes)).mapIndexed { i, d -> RecordHit(i, d) }
                        }.getOrDefault(emptyList())
                    } else {
                        RecordStream.scan(bytes)
                    }
                }
            Memory.from(hits.filter { it.decoded.crcOk })
        }
}
