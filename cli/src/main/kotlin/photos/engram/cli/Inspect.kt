package photos.engram.cli

import photos.engram.format.jpeg.Entropy
import photos.engram.format.jpeg.ExtendedXmp
import photos.engram.format.jpeg.Filler
import photos.engram.format.jpeg.Iptc
import photos.engram.format.jpeg.JpegCodec
import photos.engram.format.jpeg.JpegFormatException
import photos.engram.format.jpeg.MarkerOnly
import photos.engram.format.jpeg.MpfInspector
import photos.engram.format.jpeg.Segment
import photos.engram.format.jpeg.TrailerData
import photos.engram.format.jpeg.isExifApp1
import photos.engram.format.jpeg.isExtendedXmpApp1
import photos.engram.format.jpeg.isMpfApp2
import photos.engram.format.jpeg.isXmpApp1
import photos.engram.format.jpeg.xmpPacket
import photos.engram.format.mp4.Mp4Codec
import photos.engram.format.mp4.Mp4Files
import photos.engram.format.png.PngCodec
import photos.engram.format.records.AudioPayload
import photos.engram.format.records.RecordHit
import photos.engram.format.records.RecordKind
import photos.engram.format.records.RecordStream
import photos.engram.format.xmp.XmpCoreEngine
import java.io.File

internal fun inspect(a: Args) {
    val file = File(a.required("in"))
    require(file.isFile) { "input not found: ${file.path}" }
    when (detect(readHead(file))) {
        Container.JPEG -> inspectJpeg(file.readBytes())
        Container.PNG -> inspectPng(file.readBytes())
        Container.MP4 -> inspectMp4(file)
    }
}

private fun markerName(m: Int): String =
    when (m) {
        0xD8 -> "SOI"
        0xD9 -> "EOI"
        0xDA -> "SOS"
        0xDB -> "DQT"
        0xC4 -> "DHT"
        0xFE -> "COM"
        in 0xC0..0xCF -> "SOF${m - 0xC0}"
        in 0xD0..0xD7 -> "RST${m - 0xD0}"
        in 0xE0..0xEF -> "APP${m - 0xE0}"
        else -> "0x" + m.toString(16).uppercase()
    }

private fun segmentTag(p: Segment): String =
    when {
        p.isXmpApp1() -> " xmp"
        p.isExtendedXmpApp1() -> " extended-xmp"
        p.isExifApp1() -> " exif"
        p.isMpfApp2() -> " mpf"
        Iptc.isIptcApp13(p) -> " iptc"
        else -> ""
    }

private fun inspectJpeg(bytes: ByteArray) {
    val parts = JpegCodec.parse(bytes)
    println("container: jpeg, ${bytes.size} bytes")
    var pos = 0
    var packet: String? = null
    for (p in parts) {
        when (p) {
            is Segment -> {
                println("  @$pos ${markerName(p.marker)} len=${p.raw.size}${segmentTag(p)}")
                if (p.isXmpApp1()) packet = p.xmpPacket()
            }
            is MarkerOnly -> println("  @$pos ${markerName(p.marker)}")
            is Entropy -> println("  @$pos entropy len=${p.raw.size}")
            is Filler -> println("  @$pos filler len=${p.raw.size}")
            is TrailerData -> println("  @$pos trailer len=${p.raw.size}")
        }
        pos += p.raw.size
    }
    printXmp(packet)
    val extended =
        try {
            ExtendedXmp.collect(parts)?.let { "ok, guid ${it.guid}" } ?: "absent"
        } catch (e: JpegFormatException) {
            "BROKEN: ${e.message}"
        }
    println("extended xmp: $extended")
    parts
        .filterIsInstance<Segment>()
        .firstOrNull { Iptc.isIptcApp13(it) }
        ?.let { println("iptc caption: ${Iptc.readCaption(it.payload) ?: "(unreadable)"}") }
    printRecords("trailer", parts.filterIsInstance<TrailerData>().flatMap { RecordStream.scan(it.raw) })
    println("carve scan over whole file: ${RecordStream.scan(bytes).size} record(s)")
    val mpf = MpfInspector.inspect(bytes)
    when {
        !mpf.present -> println("mpf: absent")
        mpf.valid -> println("mpf: present, ${mpf.images.size} image(s), offsets valid")
        else -> println("mpf: PRESENT BUT BROKEN: ${mpf.problems}")
    }
    packet?.let {
        if (it.contains("MotionPhoto") || it.contains("MicroVideo")) {
            println("motion photo markers present in xmp (phase 0 landmine 2: coexistence rules)")
        }
    }
}

private fun printXmp(packet: String?) {
    if (packet == null) {
        println("xmp: absent")
        return
    }
    val s = XmpCoreEngine().read(packet)
    println("xmp: present, ${packet.length} chars")
    println("  dc:description: ${s.description ?: "(none)"}")
    if (s.hasEngram) {
        println("  engram: spec=${s.specVersion} payloadLength=${s.payloadLength} recordCount=${s.recordCount}")
    } else {
        println("  engram: no properties")
    }
    s.extendedXmpGuid?.let { println("  xmpNote:HasExtendedXMP: $it") }
}

private fun printRecords(
    where: String,
    hits: List<RecordHit>,
) {
    println("records in $where: ${hits.size}")
    for (h in hits) {
        val d = h.decoded
        val r = d.record
        val kind = r?.kind?.name ?: "unknown(${d.kindCode})"
        val detail =
            when (r?.kind) {
                RecordKind.Note -> "\"${r.payload.decodeToString().take(60)}\""
                RecordKind.Audio ->
                    AudioPayload
                        .decode(r.payload)
                        ?.let { "${it.first}, ${it.second.size} audio bytes" } ?: "malformed audio payload"
                else -> "${r?.payload?.size ?: 0} payload bytes"
            }
        val identity = r?.let { " id=${it.idHex.take(8)} by=${it.writer}" }.orEmpty()
        println("  @${h.offset} $kind ts=${r?.tsMillis ?: "?"} crc=${if (d.crcOk) "ok" else "BAD"}$identity $detail")
    }
}

private fun inspectPng(bytes: ByteArray) {
    val file = PngCodec.parse(bytes)
    println("container: png, ${bytes.size} bytes")
    var packet: String? = null
    for (c in file.chunks) {
        val xmpTag = PngCodec.xmpPacket(c)?.also { packet = it }?.let { " xmp" } ?: ""
        println("  ${c.type} len=${c.data.size} crc=${if (c.crcOk) "ok" else "BAD"}$xmpTag")
    }
    if (file.trailer.isNotEmpty()) println("  trailing junk after IEND: ${file.trailer.size} bytes")
    printXmp(packet)
    val records = PngCodec.engramRecords(file)
    val chunkCount = PngCodec.engramChunkCount(file)
    if (chunkCount != records.size) {
        println("WARNING: $chunkCount egRm chunk(s) but only ${records.size} decode as exact records")
    }
    printRecords("egRm chunks", records.mapIndexed { i, d -> RecordHit(i, d) })
}

private fun inspectMp4(file: File) {
    println("container: mp4, ${file.length()} bytes")
    for (b in Mp4Files.topLevel(file)) {
        val tag = if (Mp4Codec.isEngramBox(b)) " engram" else ""
        println("  @${b.offset} ${b.type} len=${b.boxLength}$tag")
    }
    printRecords("engram uuid box", Mp4Files.readRecords(file))
}
