package cam.engram.format.read

import cam.engram.format.mp4.Mp4Caption
import cam.engram.format.mp4.Mp4Channels
import cam.engram.format.mp4.Mp4Codec
import cam.engram.format.mp4.Mp4Files
import cam.engram.format.xmp.XmpEngine
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.SeekableByteChannel

/**
 * File-based twin of [ContainerExtraction.inspect] for JVM tooling: photos are
 * small enough to read whole, but MP4 stays streaming (box walk + bounded moov
 * read) so inspecting a large video never loads it into memory.
 */
object ExtractionFiles {
    // largest moov box we will read for a caption; matches the ExtendedXMP
    // anti-bomb bound (spec sec 9), far above any real camera moov
    private const val MOOV_READ_CAP = 64L shl 20

    fun inspect(
        file: File,
        xmp: XmpEngine,
    ): Extraction? {
        val head = file.inputStream().use { it.readNBytes(16) }
        return when (ContainerExtraction.detect(head)) {
            ContainerType.JPEG, ContainerType.PNG -> ContainerExtraction.inspect(file.readBytes(), xmp)
            ContainerType.MP4 -> inspectMp4(file, MOOV_READ_CAP)
            null -> null
        }
    }

    /**
     * Channel twin for callers holding a content-resolver descriptor (the app's
     * verifier): records, integrity, and caption without loading the video.
     */
    fun inspectMp4(
        ch: SeekableByteChannel,
        moovCap: Long = MOOV_READ_CAP,
    ): Extraction {
        val boxes =
            runCatching { Mp4Channels.topLevel(ch) }
                .getOrElse {
                    return ContainerExtraction.unreadable(
                        ContainerType.MP4,
                        "mp4 does not parse: ${it.message}",
                    )
                }
        val engram = boxes.lastOrNull { Mp4Codec.isEngramBox(it) }
        // an oversized or truncated engram box reads as zero frames: the span check
        // below then reports the carrier damaged instead of failing the whole file
        val hits = runCatching { Mp4Channels.readRecords(ch) }.getOrDefault(emptyList())
        val integrity =
            ContainerExtraction.engramBoxIntegrity(
                engram?.let { (it.boxLength - it.headerLength).toInt() },
                hits.sumOf { it.decoded.byteLength },
            )
        val caption =
            boxes
                .lastOrNull { it.type == "moov" }
                ?.takeIf { it.boxLength <= moovCap }
                ?.let { runCatching { Mp4Channels.readMoovBox(ch) }.getOrNull() }
                ?.let { Mp4Caption.readCaptionFromMoovBox(it) }
        return Extraction(
            container = ContainerType.MP4,
            integrity = integrity,
            records = hits.map { it.decoded },
            xmpSummary = null,
            extendedXmpStatus = "absent",
            mpf = null,
            motionMarkers = false,
            iptcCaption = null,
            mp4Caption = caption,
        )
    }

    internal fun inspectMp4(
        file: File,
        moovCap: Long,
    ): Extraction {
        val boxes =
            runCatching { Mp4Files.topLevel(file) }
                .getOrElse {
                    return ContainerExtraction.unreadable(
                        ContainerType.MP4,
                        "mp4 does not parse: ${it.message}",
                    )
                }
        val hits = Mp4Files.readRecords(file)
        val engram = boxes.lastOrNull { Mp4Codec.isEngramBox(it) }
        val integrity =
            ContainerExtraction.engramBoxIntegrity(
                engram?.let { (it.boxLength - it.headerLength).toInt() },
                hits.sumOf { it.decoded.byteLength },
            )
        val moov = boxes.lastOrNull { it.type == "moov" }
        // a moov above the cap is skipped, not failed: the caption is best-effort while
        // records and integrity never require loading the video
        val caption =
            moov?.takeIf { it.boxLength <= moovCap }?.let {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(it.offset)
                    val moovBytes = ByteArray(it.boxLength.toInt())
                    raf.readFully(moovBytes)
                    Mp4Caption.readCaptionFromMoovBox(moovBytes)
                }
            }
        return Extraction(
            container = ContainerType.MP4,
            integrity = integrity,
            records = hits.map { it.decoded },
            xmpSummary = null,
            extendedXmpStatus = "absent",
            mpf = null,
            motionMarkers = false,
            iptcCaption = null,
            mp4Caption = caption,
        )
    }
}
