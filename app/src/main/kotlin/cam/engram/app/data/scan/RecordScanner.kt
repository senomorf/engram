package cam.engram.app.data.scan

import cam.engram.app.data.media.ContentAccess
import cam.engram.format.ByteArrayBuilder
import cam.engram.format.Digests
import cam.engram.format.archive.EngramArchive
import cam.engram.format.mp4.Mp4Channels
import cam.engram.format.png.PngCodec
import cam.engram.format.read.Memory
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordStream
import cam.engram.format.records.ScanBudget
import cam.engram.format.toHex

class ScanOutcome(
    val recordCount: Int,
    val payloadLength: Long,
    val recordsBlob: ByteArray?,
    val searchableText: String,
    // sha-256 content hash of the media for cache-orphan export (finding 9); videos are
    // hashed by streaming the channel, never by loading them whole
    val contentHash: String,
    // idHexes of the CRC-valid frames in this scan, so write-back can verify the exact
    // expected records landed without a second read (finding B)
    val presentIds: Set<String>,
    // false when the container parsed but is structurally incomplete (a png truncated before its
    // terminal IEND keeps every record chunk yet is not a valid file): write-back must not delete
    // the backup for such a file (finding F2)
    val structurallyComplete: Boolean,
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
        val read = readTarget(uri, isVideo, mime) ?: return null
        val hash = read.bytes?.let { EngramArchive.contentHashName(it) } ?: videoHash(uri).orEmpty()
        return outcome(read.frames, hash, read.structurallyComplete)
    }

    // one extra streaming pass on the scans that already decided to read the file, so a
    // video cache orphan can still export by identity (finding 9); constant memory
    private fun videoHash(uri: String): String? = access.withChannel(uri) { Digests.sha256Hex(it) }

    /**
     * The idHexes of the CRC-valid frames currently in the target, opaque
     * (unknown kind or version) frames included. Write-back recovery uses this
     * to tell a completed write (every expected id present) from an interrupted
     * one (frames missing), instead of merely re-parsing the container.
     */
    fun presentIds(
        uri: String,
        isVideo: Boolean,
        mime: String,
    ): Set<String> =
        (readTarget(uri, isVideo, mime)?.frames ?: emptyList())
            // the id sits at frame offset 8..24 (frozen envelope), so no decode is needed
            // and carried opaque frames count toward completion like typed records
            .map { it.copyOfRange(8, 24).toHex() }
            .toSet()

    // the target read: raw frame bytes plus, for images, the whole media bytes (for a one-read
    // content hash) and whether the container is structurally complete (finding F2)
    private class TargetRead(
        val frames: List<ByteArray>,
        val bytes: ByteArray?,
        val structurallyComplete: Boolean,
    )

    /**
     * Reads the target once and returns the raw bytes of every CRC-valid record frame (in file
     * order) plus, for images, the whole media bytes so a caller can content-hash without a second
     * read, and whether the container is structurally complete. Videos stream, so their bytes are
     * null and they are not hashed here. Keeping raw frame bytes (rather than re-encoding decoded
     * records) preserves unknown/future kinds a rewriter must not silently drop. null only when the
     * target could not be read at all.
     */
    private fun readTarget(
        uri: String,
        isVideo: Boolean,
        mime: String,
    ): TargetRead? {
        if (isVideo) {
            // mp4 box-span integrity is enforced by the reader: a truncated tail box drops the
            // records, so a records-present video is structurally sound here
            val frames = videoFrames(uri) ?: return null
            return TargetRead(frames, null, structurallyComplete = true)
        }
        val bytes = access.readBytes(uri) ?: return null
        val (frames, complete) = carvePhoto(bytes, mime)
        return TargetRead(frames, bytes, complete)
    }

    // frames plus whether the container is structurally complete: a png parsed past a clean
    // truncation keeps its records but loses its terminal IEND, which write-back must not accept
    private fun carvePhoto(
        bytes: ByteArray,
        mime: String,
    ): Pair<List<ByteArray>, Boolean> =
        if (mime == "image/png") {
            runCatching {
                val file = PngCodec.parse(bytes)
                PngCodec.engramFrames(file) to PngCodec.isComplete(file)
            }.getOrElse { emptyList<ByteArray>() to false }
        } else {
            // jpeg records sit after EOI, so a truncation that drops them also drops EOI; a
            // records-present carve is structurally sound (any unknown image container likewise).
            // The carve is budgeted (review N8): media arrives from outside the app, and a region
            // dense in `EGRM` bytes would otherwise cost quadratic CRC work in a background scan.
            // An exhausted budget means the read is incomplete, so it reports as not structurally
            // complete: verify must not bless it and recovery must not settle on it.
            val budget = ScanBudget(carveBudgetBytes(bytes.size))
            val frames =
                RecordStream
                    .scan(bytes, budget = budget)
                    .filter { it.decoded.crcOk }
                    .map { bytes.copyOfRange(it.offset, it.offset + it.decoded.byteLength) }
            frames to !budget.exhausted
        }

    private fun videoFrames(uri: String): List<ByteArray>? =
        access.withChannel(uri) { ch ->
            runCatching { Mp4Channels.readRawFrames(ch) }.getOrElse { emptyList() }
        }

    // a legitimate carve CRCs roughly the payload once, so 16x the file (floored generously)
    // is far above any honest read and still bounds a hostile one to a single linear pass
    private fun carveBudgetBytes(size: Int): Long = maxOf(MIN_CARVE_BUDGET_BYTES, size.toLong() * CARVE_BUDGET_FACTOR)

    private fun outcome(
        frames: List<ByteArray>,
        contentHash: String,
        structurallyComplete: Boolean,
    ): ScanOutcome {
        if (frames.isEmpty()) return ScanOutcome(0, 0, null, "", contentHash, emptySet(), structurallyComplete)
        val blob = ByteArrayBuilder()
        var payload = 0L
        frames.forEach {
            blob.append(it)
            payload += it.size
        }
        val text = Memory.fromRecords(frames.mapNotNull { EngramRecord.decodeAt(it, 0)?.record }).searchableText()
        val ids = frames.map { it.copyOfRange(8, 24).toHex() }.toSet()
        return ScanOutcome(frames.size, payload, blob.toByteArray(), text, contentHash, ids, structurallyComplete)
    }

    private companion object {
        const val CARVE_BUDGET_FACTOR = 16L
        const val MIN_CARVE_BUDGET_BYTES = 64L * 1024 * 1024
    }
}
