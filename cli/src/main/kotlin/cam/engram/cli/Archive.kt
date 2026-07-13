package cam.engram.cli

import cam.engram.format.Digests
import cam.engram.format.archive.EngramArchive
import cam.engram.format.mp4.Mp4Channels
import cam.engram.format.read.ContainerExtraction
import java.io.File
import java.io.FileInputStream

/**
 * Reference Engram Archive export: reads one media file's records and writes
 * the same plaintext JSON + record-log + audio layout the Android app produces,
 * so a spec reader can reproduce it without the app.
 */
internal fun archive(a: Args) {
    val input = File(a.required("in"))
    val outDir = File(a.required("out"))
    require(input.isFile) { "input not found: ${input.path}" }
    outDir.mkdirs()

    val x = extract(input)
    val records = x.records.mapNotNull { it.toEngramRecord() }
    // the byte-exact log carries opaque frames (unknown kinds or versions) too. An mp4 can be
    // multi-gb, so hash and carve it by streaming the channel, never loading it whole; images are
    // bounded, so a single read is fine (finding F5)
    val hash: String
    val rawFrames: List<ByteArray>
    if (x.container == Container.MP4) {
        hash = FileInputStream(input).use { Digests.sha256Hex(it.channel) }
        rawFrames = FileInputStream(input).use { Mp4Channels.readRawFrames(it.channel) }
    } else {
        val bytes = input.readBytes()
        hash = EngramArchive.contentHashName(bytes)
        rawFrames = ContainerExtraction.rawFrames(bytes)
    }
    if (rawFrames.isEmpty()) {
        println("no engram records in ${input.path}, nothing to archive")
        return
    }
    val rendered = EngramArchive.render(EngramArchive.Item(hash, input.name, records, rawFrames))
    val written = mutableListOf<EngramArchive.ManifestFile>()

    fun put(
        name: String,
        data: ByteArray,
    ) {
        File(outDir, name).writeBytes(data)
        written += EngramArchive.ManifestFile(name, EngramArchive.contentHashName(data))
    }
    put("$hash.json", rendered.json.encodeToByteArray())
    val log = rendered.recordLog
    val logName = rendered.recordLogName
    if (log != null && logName != null) put(logName, log)
    rendered.audio.forEach { put(it.fileName, it.data) }
    File(outDir, "manifest.json").writeText(EngramArchive.manifest(1, written))
    println(
        "archived ${rawFrames.size} record(s), ${rendered.audio.size} audio blob(s) to ${outDir.path}",
    )
}
