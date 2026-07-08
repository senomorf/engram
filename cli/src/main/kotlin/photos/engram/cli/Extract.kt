package photos.engram.cli

import photos.engram.format.jpeg.ExtendedXmp
import photos.engram.format.jpeg.Iptc
import photos.engram.format.jpeg.JpegCodec
import photos.engram.format.jpeg.JpegFormatException
import photos.engram.format.jpeg.MpfInspector
import photos.engram.format.jpeg.MpfReport
import photos.engram.format.jpeg.Segment
import photos.engram.format.jpeg.TrailerData
import photos.engram.format.jpeg.isXmpApp1
import photos.engram.format.jpeg.xmpPacket
import photos.engram.format.mp4.Mp4Caption
import photos.engram.format.mp4.Mp4Files
import photos.engram.format.png.PngCodec
import photos.engram.format.records.DecodedRecord
import photos.engram.format.records.RecordStream
import photos.engram.format.xmp.XmpCoreEngine
import photos.engram.format.xmp.XmpSummary
import java.io.File
import java.io.RandomAccessFile

internal class ExtractedRecord(
    val kind: String,
    val idHex: String,
    val writer: String,
    val tsMillis: Long,
    val crcOk: Boolean,
    val payload: ByteArray?,
    val record: photos.engram.format.records.EngramRecord?,
) {
    fun toEngramRecord() = record?.takeIf { crcOk }
}

internal data class Extraction(
    val container: Container,
    val records: List<ExtractedRecord>,
    val xmpSummary: XmpSummary?,
    val extendedStatus: String,
    val mpf: MpfReport?,
    val motionMarkers: Boolean,
    val iptcCaption: String?,
    val mp4Caption: String?,
    val pngEngramChunks: Int = 0,
)

internal fun extract(file: File): Extraction =
    when (detect(readHead(file))) {
        Container.JPEG -> extractJpeg(file.readBytes())
        Container.PNG -> extractPng(file.readBytes())
        Container.MP4 -> extractMp4(file)
    }

private fun toExtracted(decoded: List<DecodedRecord>): List<ExtractedRecord> =
    decoded.map { d ->
        ExtractedRecord(
            kind = d.record?.kind?.name ?: "unknown(${d.kindCode})",
            idHex = d.record?.idHex.orEmpty(),
            writer = d.record?.writer.orEmpty(),
            tsMillis = d.record?.tsMillis ?: 0,
            crcOk = d.crcOk,
            payload = d.record?.payload,
            record = d.record,
        )
    }

private fun extractJpeg(bytes: ByteArray): Extraction {
    val parts = JpegCodec.parse(bytes)
    val packet = parts.filterIsInstance<Segment>().firstOrNull { it.isXmpApp1() }?.xmpPacket()
    val extended =
        try {
            if (ExtendedXmp.collect(parts) != null) "ok" else "absent"
        } catch (e: JpegFormatException) {
            "broken: ${e.message}"
        }
    val records = parts.filterIsInstance<TrailerData>().flatMap { RecordStream.scan(it.raw) }.map { it.decoded }
    val iptc =
        parts
            .filterIsInstance<Segment>()
            .firstOrNull { Iptc.isIptcApp13(it) }
            ?.let { Iptc.readCaption(it.payload) }
    return Extraction(
        container = Container.JPEG,
        records = toExtracted(records),
        xmpSummary = packet?.let { XmpCoreEngine().read(it) },
        extendedStatus = extended,
        mpf = MpfInspector.inspect(bytes),
        motionMarkers = packet?.let { it.contains("MotionPhoto") || it.contains("MicroVideo") } ?: false,
        iptcCaption = iptc,
        mp4Caption = null,
    )
}

private fun extractPng(bytes: ByteArray): Extraction {
    val file = PngCodec.parse(bytes)
    val packet = file.chunks.firstNotNullOfOrNull { PngCodec.xmpPacket(it) }
    return Extraction(
        container = Container.PNG,
        records = toExtracted(PngCodec.engramRecords(file)),
        xmpSummary = packet?.let { XmpCoreEngine().read(it) },
        extendedStatus = "absent",
        mpf = null,
        motionMarkers = false,
        iptcCaption = null,
        mp4Caption = null,
        pngEngramChunks = PngCodec.engramChunkCount(file),
    )
}

private fun extractMp4(file: File): Extraction {
    val boxes = Mp4Files.topLevel(file)
    val moov = boxes.lastOrNull { it.type == "moov" }
    val caption =
        moov?.let {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(it.offset)
                val moovBytes = ByteArray(it.boxLength.toInt())
                raf.readFully(moovBytes)
                Mp4Caption.readCaptionFromMoovBox(moovBytes)
            }
        }
    return Extraction(
        container = Container.MP4,
        records = toExtracted(Mp4Files.readRecords(file).map { it.decoded }),
        xmpSummary = null,
        extendedStatus = "absent",
        mpf = null,
        motionMarkers = false,
        iptcCaption = null,
        mp4Caption = caption,
    )
}
