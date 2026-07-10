package cam.engram.cli

import cam.engram.format.Md5
import cam.engram.format.archive.ArchiveReader
import cam.engram.format.archive.EngramArchive
import cam.engram.format.records.RecordStream
import cam.engram.format.toHex
import java.io.File

/**
 * Proves an Engram Archive complete and readable (spec sec 11): every
 * manifest-listed file present with its hash, every record log fully decodable
 * with CRC-valid frames matching the JSON view's count, every audio blob
 * present. Exit 0 valid, 1 invalid.
 */
internal fun validateArchive(a: Args): Int {
    val dir = File(a.required("in"))
    require(dir.isDirectory) { "archive directory not found: ${dir.path}" }
    val manifestFile = File(dir, "manifest.json")
    if (!manifestFile.isFile) {
        println("invalid: manifest.json missing")
        return 1
    }
    val manifest = ArchiveReader.parseManifest(manifestFile.readText())
    val problems = mutableListOf<String>()
    if (manifest.manifestVersion < 2) {
        problems += "manifest v${manifest.manifestVersion} carries no file inventory (pre-v2 archive)"
    }
    // v3 inventories hash with sha-256; v2 predates the switch and used md5
    val digest: (ByteArray) -> String =
        if (manifest.manifestVersion >= 3) EngramArchive::contentHashName else { b -> Md5.of(b).toHex() }
    for (f in manifest.files) {
        val file = File(dir, f.name)
        when {
            !file.isFile -> problems += "missing: ${f.name}"
            digest(file.readBytes()) != f.hash -> problems += "hash mismatch: ${f.name}"
        }
    }
    var items = 0
    dir
        .listFiles { f -> f.name.endsWith(".json") && f.name != "manifest.json" }
        ?.sortedBy { it.name }
        ?.forEach { jsonFile ->
            items++
            problems += itemProblems(dir, ArchiveReader.parseItem(jsonFile.readText()), jsonFile.name)
        }
    if (manifest.itemCount != items) {
        problems += "manifest itemCount ${manifest.itemCount} but $items item document(s) found"
    }
    return if (problems.isEmpty()) {
        println("archive valid: $items item(s), ${manifest.files.size} file(s) verified")
        0
    } else {
        problems.forEach { println("invalid: $it") }
        1
    }
}

private fun itemProblems(
    dir: File,
    item: ArchiveReader.ItemView,
    jsonName: String,
): List<String> {
    val problems = mutableListOf<String>()
    val logName = item.recordLog
    if (logName == null) {
        if (item.frameCount > 0) problems += "$jsonName: frameCount ${item.frameCount} but no record log"
    } else {
        val logFile = File(dir, logName)
        if (!logFile.isFile) {
            problems += "missing record log: $logName"
        } else {
            val bytes = logFile.readBytes()
            val hits = RecordStream.decodeSequence(bytes)
            val consumed = hits.sumOf { it.decoded.byteLength }
            if (consumed != bytes.size) problems += "$logName: undecodable bytes after ${hits.size} frame(s)"
            if (hits.size != item.frameCount) {
                problems += "$logName: ${hits.size} frame(s) but the view claims ${item.frameCount}"
            }
            if (hits.any { !it.decoded.crcOk }) problems += "$logName: corrupt frame(s)"
        }
    }
    item.audio.forEach { if (!File(dir, it).isFile) problems += "missing audio: $it" }
    return problems
}
