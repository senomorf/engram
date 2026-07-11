package cam.engram.cli

import cam.engram.format.jpeg.MpfReport
import cam.engram.format.read.CarrierIntegrity
import cam.engram.format.read.ExtractionFiles
import cam.engram.format.records.DecodedRecord
import cam.engram.format.xmp.XmpCoreEngine
import cam.engram.format.xmp.XmpSummary
import java.io.File

internal class ExtractedRecord(
    val kind: String,
    val idHex: String,
    val writer: String,
    val tsMillis: Long,
    val crcOk: Boolean,
    val payload: ByteArray?,
    val record: cam.engram.format.records.EngramRecord?,
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
    // carrier integrity survives the projection so verify can judge it (Unreadable throws)
    val integrity: String = "intact",
)

// one shared reader (core-format ContainerExtraction) feeds this tool and the in-app
// verifier; this view keeps the cli's record projection and its throwing error contract
internal fun extract(file: File): Extraction {
    val x =
        ExtractionFiles.inspect(file, XmpCoreEngine())
            ?: throw IllegalArgumentException("unrecognized container (jpeg, png, mp4 supported)")
    (x.integrity as? CarrierIntegrity.Unreadable)?.let { throw IllegalArgumentException(it.detail) }
    return Extraction(
        container = Container.valueOf(x.container.name),
        records = toExtracted(x.records),
        xmpSummary = x.xmpSummary,
        extendedStatus = x.extendedXmpStatus,
        mpf = x.mpf,
        motionMarkers = x.motionMarkers,
        iptcCaption = x.iptcCaption,
        mp4Caption = x.mp4Caption,
        pngEngramChunks = x.pngEngramChunks,
        integrity = if (x.integrity is CarrierIntegrity.CarrierDamaged) "damaged" else "intact",
    )
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
