package cam.engram.cli

import cam.engram.format.Md5
import cam.engram.format.archive.ArchiveReader
import cam.engram.format.archive.EngramArchive
import cam.engram.format.records.RecordStream
import cam.engram.format.toHex
import java.io.File

/**
 * Proves an Engram Archive complete and readable (spec sec 11): the inventory
 * covers exactly the files beside the manifest (both directions, v3+), names are
 * unique and never escape the directory, every listed file matches its hash,
 * every record log fully decodes with CRC-valid frames matching the JSON view's
 * count, every audio blob present. Exit 0 valid, 1 invalid.
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
    val listed = mutableSetOf<String>()
    for (f in manifest.files) {
        val file = File(dir, f.name)
        when {
            unsafeName(f.name) -> problems += "unsafe name in inventory: ${f.name}"
            !listed.add(f.name) -> problems += "duplicate inventory entry: ${f.name}"
            !file.isFile -> problems += "missing: ${f.name}"
            digest(file.readBytes()) != f.hash -> problems += "hash mismatch: ${f.name}"
        }
    }
    // pre-v3 archives predate the completeness promise: only their listed files are judged
    val inventory = if (manifest.manifestVersion >= 3) listed else null
    if (inventory != null) problems += inventoryProblems(dir, manifest, inventory)
    var items = 0
    dir
        .listFiles { f -> f.name.endsWith(".json") && f.name != "manifest.json" }
        ?.sortedBy { it.name }
        ?.forEach { jsonFile ->
            items++
            problems += itemProblems(dir, ArchiveReader.parseItem(jsonFile.readText()), jsonFile.name, inventory)
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

// a name with a separator or a dot-dot segment could make the validator read,
// and thereby vouch for, content outside the archive directory
private fun unsafeName(name: String) = name.contains('/') || name.contains('\\') || name.contains("..")

// the spec sec 11 completeness promise, files-to-inventory direction (the
// inventory-to-files direction is the per-file missing check above)
private fun inventoryProblems(
    dir: File,
    manifest: ArchiveReader.Manifest,
    listed: Set<String>,
): List<String> {
    val problems = mutableListOf<String>()
    if (manifest.archive != "engram") problems += "archive marker missing or wrong: ${manifest.archive}"
    dir
        .listFiles()
        ?.filter { it.isFile && it.name != "manifest.json" && it.name !in listed }
        ?.sortedBy { it.name }
        ?.forEach { problems += "on disk but not inventoried: ${it.name}" }
    return problems
}

private fun itemProblems(
    dir: File,
    item: ArchiveReader.ItemView,
    jsonName: String,
    inventory: Set<String>?,
): List<String> {
    val problems = mutableListOf<String>()
    val logName = item.recordLog
    if (logName == null) {
        if (item.frameCount > 0) problems += "$jsonName: frameCount ${item.frameCount} but no record log"
    } else if (unsafeName(logName)) {
        problems += "$jsonName: unsafe record log name: $logName"
    } else {
        if (inventory != null && logName !in inventory) problems += "$jsonName: record log not inventoried: $logName"
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
    item.audio.forEach {
        when {
            unsafeName(it) -> problems += "$jsonName: unsafe audio name: $it"
            inventory != null && it !in inventory -> problems += "$jsonName: audio not inventoried: $it"
            !File(dir, it).isFile -> problems += "missing audio: $it"
        }
    }
    return problems
}
