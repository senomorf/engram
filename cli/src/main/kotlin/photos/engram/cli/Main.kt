package photos.engram.cli

import photos.engram.format.jpeg.JpegCodec
import photos.engram.format.jpeg.JpegEmbedder
import photos.engram.format.jpeg.Entropy
import photos.engram.format.jpeg.Filler
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
import photos.engram.format.png.PngEmbedder
import photos.engram.format.records.AudioPayload
import photos.engram.format.records.EngramRecord
import photos.engram.format.records.RecordHit
import photos.engram.format.records.RecordKind
import photos.engram.format.records.RecordStream
import photos.engram.format.startsWith
import photos.engram.format.testing.SyntheticMedia
import photos.engram.format.xmp.XmpCoreEngine
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """engram lab cli, phase 0

usage:
  engram generate --in <file> --out <file> [--note <text>] [--audio <file> [--mime <type>]]
  engram inspect --in <file>
  engram selftest

generate embeds engram records and mirrors the note into standard caption
fields (xmp dc:description in v0). inspect reports everything engram can see."""

fun main(args: Array<String>) {
    try {
        when (args.getOrNull(0)) {
            "generate" -> generate(Args(args))
            "inspect", "verify" -> inspect(Args(args))
            "selftest" -> selftest()
            else -> {
                println(USAGE)
                exitProcess(2)
            }
        }
    } catch (e: Exception) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
}

private class Args(private val raw: Array<String>) {
    fun opt(name: String): String? {
        val i = raw.indexOf("--$name")
        return if (i >= 0 && i + 1 < raw.size) raw[i + 1] else null
    }

    fun required(name: String): String = opt(name) ?: throw IllegalArgumentException("missing --$name")
}

private enum class Container { JPEG, PNG, MP4 }

private fun detect(head: ByteArray): Container = when {
    head.size > 2 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> Container.JPEG
    head.size >= 8 && head.startsWith(PngCodec.SIGNATURE) -> Container.PNG
    head.size >= 12 && head.copyOfRange(4, 8).decodeToString() == "ftyp" -> Container.MP4
    else -> throw IllegalArgumentException("unrecognized container (jpeg, png, mp4 supported)")
}

private fun readHead(file: File): ByteArray = file.inputStream().use { it.readNBytes(16) }

private fun buildRecords(a: Args): Pair<List<EngramRecord>, String?> {
    val now = System.currentTimeMillis()
    val records = mutableListOf<EngramRecord>()
    val note = a.opt("note")
    note?.let { records += EngramRecord(RecordKind.Note, now, it.encodeToByteArray()) }
    a.opt("audio")?.let { path ->
        val f = File(path)
        require(f.isFile) { "audio file not found: $path" }
        val mime = a.opt("mime") ?: when (f.extension.lowercase()) {
            "ogg", "opus", "oga" -> "audio/ogg"
            "m4a", "aac", "mp4" -> "audio/mp4"
            else -> throw IllegalArgumentException("cannot infer mime for .${f.extension}, pass --mime")
        }
        records += EngramRecord(RecordKind.Audio, now, AudioPayload.encode(mime, f.readBytes()))
    }
    require(records.isNotEmpty()) { "nothing to embed, pass --note and/or --audio" }
    return records to note
}

private fun generate(a: Args) {
    val input = File(a.required("in"))
    val output = File(a.required("out"))
    require(input.isFile) { "input not found: ${input.path}" }
    require(input.canonicalPath != output.canonicalPath) { "refusing to overwrite input, use a distinct --out" }
    val (records, note) = buildRecords(a)
    when (detect(readHead(input))) {
        Container.JPEG -> output.writeBytes(JpegEmbedder(XmpCoreEngine()).embed(input.readBytes(), records, note))
        Container.PNG -> output.writeBytes(PngEmbedder(XmpCoreEngine()).embed(input.readBytes(), records, note))
        Container.MP4 -> {
            Mp4Files.appendRecords(input, output, records)
            println("note: mp4 caption mirroring is not implemented in v0")
        }
    }
    println("wrote ${output.path} (+${records.size} record(s))")
}

private fun inspect(a: Args) {
    val file = File(a.required("in"))
    require(file.isFile) { "input not found: ${file.path}" }
    when (detect(readHead(file))) {
        Container.JPEG -> inspectJpeg(file.readBytes())
        Container.PNG -> inspectPng(file.readBytes())
        Container.MP4 -> inspectMp4(file)
    }
}

private fun markerName(m: Int): String = when (m) {
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

private fun inspectJpeg(bytes: ByteArray) {
    val parts = JpegCodec.parse(bytes)
    println("container: jpeg, ${bytes.size} bytes")
    var pos = 0
    var packet: String? = null
    for (p in parts) {
        when (p) {
            is Segment -> {
                val tag = when {
                    p.isXmpApp1() -> " xmp"
                    p.isExtendedXmpApp1() -> " extended-xmp"
                    p.isExifApp1() -> " exif"
                    p.isMpfApp2() -> " mpf"
                    else -> ""
                }
                println("  @$pos ${markerName(p.marker)} len=${p.raw.size}$tag")
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
}

private fun printRecords(where: String, hits: List<RecordHit>) {
    println("records in $where: ${hits.size}")
    for (h in hits) {
        val d = h.decoded
        val r = d.record
        val kind = r?.kind?.name ?: "unknown(${d.kindCode})"
        val detail = when (r?.kind) {
            RecordKind.Note -> "\"${r.payload.decodeToString().take(60)}\""
            RecordKind.Audio -> AudioPayload.decode(r.payload)
                ?.let { "${it.first}, ${it.second.size} audio bytes" } ?: "malformed audio payload"
            else -> "${r?.payload?.size ?: 0} payload bytes"
        }
        println("  @${h.offset} $kind ts=${r?.tsMillis ?: "?"} crc=${if (d.crcOk) "ok" else "BAD"} $detail")
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
    val hits = file.chunks.filter { it.type == PngCodec.ENGRAM_CHUNK }
        .mapNotNull { c -> EngramRecord.decodeAt(c.data, 0)?.let { RecordHit(0, it) } }
    printRecords("egRm chunks", hits)
}

private fun inspectMp4(file: File) {
    println("container: mp4, ${file.length()} bytes")
    for (b in Mp4Files.topLevel(file)) {
        val tag = if (Mp4Codec.isEngramBox(b)) " engram" else ""
        println("  @${b.offset} ${b.type} len=${b.boxLength}$tag")
    }
    printRecords("engram uuid box", Mp4Files.readRecords(file))
}

private fun selftest() {
    val xmp = XmpCoreEngine()
    val note = "selftest memory"
    val noteRec = EngramRecord(RecordKind.Note, 1, note.encodeToByteArray())
    val audioRec = EngramRecord(RecordKind.Audio, 1, AudioPayload.encode("audio/ogg", ByteArray(64) { it.toByte() }))

    val jpeg = JpegEmbedder(xmp).embed(SyntheticMedia.jpegWithMpfSecondary(), listOf(noteRec, audioRec), note)
    check("jpeg records", RecordStream.scan(jpeg).count { it.decoded.crcOk } == 2)
    check("jpeg mpf intact", MpfInspector.inspect(jpeg).valid)
    val packet = JpegCodec.parse(jpeg).filterIsInstance<Segment>().first { it.isXmpApp1() }.xmpPacket()
    check("jpeg dual-write", xmp.read(packet).description == note)

    val png = PngEmbedder(xmp).embed(SyntheticMedia.png1x1(), listOf(noteRec), note)
    val pngFile = PngCodec.parse(png)
    check("png engram chunk", pngFile.chunks.any { it.type == PngCodec.ENGRAM_CHUNK })
    check("png crcs", pngFile.chunks.all { it.crcOk })
    check("png dual-write", xmp.read(pngFile.chunks.firstNotNullOf { PngCodec.xmpPacket(it) }).description == note)

    val mp4 = Mp4Codec.embed(SyntheticMedia.mp4Minimal(), listOf(noteRec, audioRec))
    check("mp4 records", Mp4Codec.readRecords(mp4).count { it.decoded.crcOk } == 2)

    println("selftest: all checks passed")
}

private fun check(name: String, ok: Boolean) {
    if (!ok) throw IllegalStateException("selftest failed: $name")
    println("  ok: $name")
}
