package photos.engram.cli

import photos.engram.format.archive.EngramArchive
import java.io.File

/**
 * Reference Engram Archive export: reads one media file's records and writes
 * the same plaintext JSON + audio layout the Android app produces, so a spec
 * reader can reproduce it without the app.
 */
internal fun archive(a: Args) {
    val input = File(a.required("in"))
    val outDir = File(a.required("out"))
    require(input.isFile) { "input not found: ${input.path}" }
    outDir.mkdirs()

    val x = extract(input)
    val records = x.records.mapNotNull { it.toEngramRecord() }
    if (records.isEmpty()) {
        println("no engram records in ${input.path}, nothing to archive")
        return
    }
    val hash = EngramArchive.contentHashName(input.readBytes())
    val rendered = EngramArchive.render(EngramArchive.Item(hash, input.name, records))
    File(outDir, "$hash.json").writeText(rendered.json)
    rendered.audio.forEach { File(outDir, it.fileName).writeBytes(it.data) }
    File(outDir, "manifest.json").writeText(EngramArchive.manifest(1))
    println("archived ${records.size} record(s), ${rendered.audio.size} audio blob(s) to ${outDir.path}")
}
