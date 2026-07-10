package cam.engram.format.read

import cam.engram.format.jpeg.ExtendedXmp
import cam.engram.format.jpeg.Iptc
import cam.engram.format.jpeg.JpegCodec
import cam.engram.format.jpeg.JpegFormatException
import cam.engram.format.jpeg.MpfInspector
import cam.engram.format.jpeg.MpfReport
import cam.engram.format.jpeg.Segment
import cam.engram.format.jpeg.TrailerData
import cam.engram.format.jpeg.isXmpApp1
import cam.engram.format.jpeg.xmpPacket
import cam.engram.format.mp4.Mp4Caption
import cam.engram.format.mp4.Mp4Codec
import cam.engram.format.png.PngCodec
import cam.engram.format.records.DecodedRecord
import cam.engram.format.records.RecordStream
import cam.engram.format.startsWith
import cam.engram.format.xmp.XmpEngine
import cam.engram.format.xmp.XmpSummary

enum class ContainerType { JPEG, PNG, MP4 }

/** Structural state of the media container and its declared Engram carriers. */
sealed interface CarrierIntegrity {
    /** the media parses and every declared engram carrier decodes */
    data object Readable : CarrierIntegrity

    /**
     * The media parses but a declared engram carrier is malformed (an egRm chunk
     * that does not decode, undecodable bytes inside the engram uuid box): frames
     * were lost even if the surviving ones read back fine.
     */
    data class CarrierDamaged(
        val detail: String,
    ) : CarrierIntegrity

    /** the media container itself does not parse */
    data class Unreadable(
        val detail: String,
    ) : CarrierIntegrity
}

/**
 * Everything a reader can learn about the engram content of one media file,
 * container-agnostic, with captions kept per source (XMP, IPTC, MP4) because
 * consumers apply different precedence to them.
 */
data class Extraction(
    val container: ContainerType,
    val integrity: CarrierIntegrity,
    val records: List<DecodedRecord>,
    val xmpSummary: XmpSummary?,
    val extendedXmpStatus: String,
    val mpf: MpfReport?,
    val motionMarkers: Boolean,
    val iptcCaption: String?,
    val mp4Caption: String?,
    val pngEngramChunks: Int = 0,
)

/** Absolute (expectation-free) survival classes, shared by app and tooling. */
enum class Survival { FULL, DAMAGED, CAPTION_ONLY, GONE, UNREADABLE }

/**
 * One shared "what is in this file" reader (design D14): detection by magic
 * bytes, per-container record scan, captions, and structural carrier state.
 * The cli layers its expectation-relative verdict on top; the app classifies
 * absolutely via [classify].
 */
object ContainerExtraction {
    /** Container by magic bytes, null when unrecognized. */
    fun detect(head: ByteArray): ContainerType? =
        when {
            head.size > 2 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> ContainerType.JPEG
            head.size >= 8 && head.startsWith(PngCodec.SIGNATURE) -> ContainerType.PNG
            head.size >= 12 && head.copyOfRange(4, 8).decodeToString() == "ftyp" -> ContainerType.MP4
            else -> null
        }

    /** Inspects [bytes]; null when the container is unrecognized. */
    fun inspect(
        bytes: ByteArray,
        xmp: XmpEngine,
    ): Extraction? =
        when (detect(bytes)) {
            ContainerType.JPEG -> inspectJpeg(bytes, xmp)
            ContainerType.PNG -> inspectPng(bytes, xmp)
            ContainerType.MP4 -> inspectMp4(bytes)
            null -> null
        }

    /**
     * Absolute survival verdict. Order matters: a damaged carrier or any
     * CRC-corrupt record downgrades the verdict, so a partial backup is never
     * reported as fully survived.
     */
    fun classify(
        extraction: Extraction?,
        captionVisible: Boolean,
    ): Survival =
        when {
            extraction == null || extraction.integrity is CarrierIntegrity.Unreadable -> Survival.UNREADABLE
            extraction.integrity is CarrierIntegrity.CarrierDamaged -> Survival.DAMAGED
            extraction.records.any { !it.crcOk } -> Survival.DAMAGED
            extraction.records.isNotEmpty() -> Survival.FULL
            captionVisible -> Survival.CAPTION_ONLY
            else -> Survival.GONE
        }

    private fun inspectJpeg(
        bytes: ByteArray,
        xmp: XmpEngine,
    ): Extraction {
        val parts =
            runCatching { JpegCodec.parse(bytes) }
                .getOrElse { return unreadable(ContainerType.JPEG, "jpeg does not parse: ${it.message}") }
        val packet = parts.filterIsInstance<Segment>().firstOrNull { it.isXmpApp1() }?.xmpPacket()
        val extended =
            try {
                if (ExtendedXmp.collect(parts) != null) "ok" else "absent"
            } catch (e: JpegFormatException) {
                "broken: ${e.message}"
            }
        // records live in the parsed post-EOI trailer; scanning only the parsed trailer
        // keeps stray magic bytes inside metadata segments from being carved as records
        val records = parts.filterIsInstance<TrailerData>().flatMap { RecordStream.scan(it.raw) }.map { it.decoded }
        val iptc =
            parts
                .filterIsInstance<Segment>()
                .firstOrNull { Iptc.isIptcApp13(it) }
                ?.let { Iptc.readCaption(it.payload) }
        return Extraction(
            container = ContainerType.JPEG,
            integrity = CarrierIntegrity.Readable,
            records = records,
            xmpSummary = packet?.let { xmp.read(it) },
            extendedXmpStatus = extended,
            mpf = MpfInspector.inspect(bytes),
            motionMarkers = packet?.let { it.contains("MotionPhoto") || it.contains("MicroVideo") } ?: false,
            iptcCaption = iptc,
            mp4Caption = null,
        )
    }

    private fun inspectPng(
        bytes: ByteArray,
        xmp: XmpEngine,
    ): Extraction {
        val file =
            runCatching { PngCodec.parse(bytes) }
                .getOrElse { return unreadable(ContainerType.PNG, "png does not parse: ${it.message}") }
        val records = PngCodec.engramRecords(file)
        val chunkCount = PngCodec.engramChunkCount(file)
        val integrity =
            if (chunkCount > records.size) {
                CarrierIntegrity.CarrierDamaged("$chunkCount egRm chunks but only ${records.size} decode")
            } else {
                CarrierIntegrity.Readable
            }
        val packet = file.chunks.firstNotNullOfOrNull { PngCodec.xmpPacket(it) }
        return Extraction(
            container = ContainerType.PNG,
            integrity = integrity,
            records = records,
            xmpSummary = packet?.let { xmp.read(it) },
            extendedXmpStatus = "absent",
            mpf = null,
            motionMarkers = false,
            iptcCaption = null,
            mp4Caption = null,
            pngEngramChunks = chunkCount,
        )
    }

    private fun inspectMp4(bytes: ByteArray): Extraction {
        val boxes =
            runCatching { Mp4Codec.topLevel(bytes) }
                .getOrElse { return unreadable(ContainerType.MP4, "mp4 does not parse: ${it.message}") }
        val engram = boxes.lastOrNull { Mp4Codec.isEngramBox(it) }
        val hits =
            engram
                ?.let {
                    RecordStream.decodeSequence(
                        bytes,
                        (it.offset + it.headerLength).toInt(),
                        (it.offset + it.boxLength).toInt(),
                    )
                }.orEmpty()
        val integrity =
            engramBoxIntegrity(
                engram?.let { (it.boxLength - it.headerLength).toInt() },
                hits.sumOf { it.decoded.byteLength },
            )
        val caption = runCatching { Mp4Caption.readCaption(bytes) }.getOrNull()
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

    // frames must fill the declared engram box exactly: an undecodable tail means the
    // carrier lost data even when the frames before it read back fine
    internal fun engramBoxIntegrity(
        span: Int?,
        consumed: Int,
    ): CarrierIntegrity =
        if (span != null && consumed < span) {
            CarrierIntegrity.CarrierDamaged("engram box holds $span bytes but only $consumed decode")
        } else {
            CarrierIntegrity.Readable
        }

    internal fun unreadable(
        container: ContainerType,
        detail: String,
    ): Extraction =
        Extraction(
            container = container,
            integrity = CarrierIntegrity.Unreadable(detail),
            records = emptyList(),
            xmpSummary = null,
            extendedXmpStatus = "absent",
            mpf = null,
            motionMarkers = false,
            iptcCaption = null,
            mp4Caption = null,
        )
}
