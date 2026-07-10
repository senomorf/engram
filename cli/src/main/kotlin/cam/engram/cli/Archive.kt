package cam.engram.cli

import cam.engram.format.archive.EngramArchive
import cam.engram.format.read.ContainerExtraction
import java.io.File

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

    val bytes = input.readBytes()
    val x = extract(input)
    val records = x.records.mapNotNull { it.toEngramRecord() }
    // the byte-exact log carries opaque frames (unknown kinds or versions) too
    val rawFrames = ContainerExtraction.rawFrames(bytes)
    if (rawFrames.isEmpty()) {
        println("no engram records in ${input.path}, nothing to archive")
        return
    }
    val hash = EngramArchive.contentHashName(bytes)
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
