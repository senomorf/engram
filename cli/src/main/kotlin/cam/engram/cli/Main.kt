package cam.engram.cli

import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.mp4.CaptionOutcome
import cam.engram.format.mp4.Mp4Files
import cam.engram.format.png.PngEmbedder
import cam.engram.format.read.ContainerExtraction
import cam.engram.format.read.ContainerType
import cam.engram.format.records.AudioPayload
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.xmp.XmpCoreEngine
import java.io.File
import java.security.SecureRandom
import kotlin.system.exitProcess

private const val USAGE = """engram lab cli, phase 0

usage:
  engram generate --in <file> --out <file> [--note <text>] [--audio <file> [--mime <type>]]
  engram inspect --in <file>
  engram verify --in <file> [--expect <file>] [--json]
  engram archive --in <file> --out <dir>
  engram selftest

generate embeds engram records, mirrors the note into standard caption fields
(xmp dc:description, iptc, mp4 comment where safe), and writes an .engram-expect
sidecar. verify judges a file against that sidecar: exit 0 intact, 3 degraded,
4 damaged. inspect prints everything engram can see."""

private const val WRITER_ID = "engram-cli"

fun main(args: Array<String>) {
    exitProcess(cliMain(args))
}

/** Process-free entry point so integration tests can call the cli in-jvm. */
fun cliMain(args: Array<String>): Int =
    try {
        when (args.getOrNull(0)) {
            "generate" -> {
                generate(Args(args))
                0
            }
            "inspect" -> {
                inspect(Args(args))
                0
            }
            "verify" -> verify(Args(args))
            "archive" -> {
                archive(Args(args))
                0
            }
            "selftest" -> {
                selftest()
                0
            }
            else -> {
                println(USAGE)
                2
            }
        }
    } catch (e: Exception) {
        System.err.println("error: ${e.message}")
        1
    }

internal class Args(
    private val raw: Array<String>,
) {
    fun opt(name: String): String? {
        val i = raw.indexOf("--$name")
        return if (i >= 0 && i + 1 < raw.size) raw[i + 1] else null
    }

    fun flag(name: String): Boolean = raw.contains("--$name")

    fun required(name: String): String = opt(name) ?: throw IllegalArgumentException("missing --$name")
}

internal enum class Container { JPEG, PNG, MP4 }

// magic-byte detection lives in core-format (shared with the app verifier); the cli
// keeps its own throwing contract for unknown files
internal fun detect(head: ByteArray): Container =
    when (ContainerExtraction.detect(head)) {
        ContainerType.JPEG -> Container.JPEG
        ContainerType.PNG -> Container.PNG
        ContainerType.MP4 -> Container.MP4
        null -> throw IllegalArgumentException("unrecognized container (jpeg, png, mp4 supported)")
    }

internal fun readHead(file: File): ByteArray = file.inputStream().use { it.readNBytes(16) }

private class Planted(
    val records: List<EngramRecord>,
    val note: String?,
    val noteId: String?,
    val audioId: String?,
    val audioMime: String?,
    val audioSha256: String?,
)

private fun buildRecords(a: Args): Planted {
    val now = System.currentTimeMillis()
    val rnd = SecureRandom()

    fun newId(): ByteArray = ByteArray(EngramRecord.ID_LENGTH).also { rnd.nextBytes(it) }
    val records = mutableListOf<EngramRecord>()
    val note = a.opt("note")
    var noteId: String? = null
    var audioId: String? = null
    var audioMime: String? = null
    var audioSha: String? = null
    note?.let {
        val rec = EngramRecord(RecordKind.Note, now, it.encodeToByteArray(), newId(), WRITER_ID)
        noteId = rec.idHex
        records += rec
    }
    a.opt("audio")?.let { path ->
        val f = File(path)
        require(f.isFile) { "audio file not found: $path" }
        val mime =
            a.opt("mime") ?: when (f.extension.lowercase()) {
                "ogg", "opus", "oga" -> "audio/ogg"
                "m4a", "aac", "mp4" -> "audio/mp4"
                else -> throw IllegalArgumentException("cannot infer mime for .${f.extension}, pass --mime")
            }
        val data = f.readBytes()
        val rec = EngramRecord(RecordKind.Audio, now, AudioPayload.encode(mime, data), newId(), WRITER_ID)
        audioId = rec.idHex
        audioMime = mime
        audioSha = sha256Hex(data)
        records += rec
    }
    require(records.isNotEmpty()) { "nothing to embed, pass --note and/or --audio" }
    return Planted(records, note, noteId, audioId, audioMime, audioSha)
}

private fun generate(a: Args) {
    val input = File(a.required("in"))
    val output = File(a.required("out"))
    require(input.isFile) { "input not found: ${input.path}" }
    require(input.canonicalPath != output.canonicalPath) { "refusing to overwrite input, use a distinct --out" }
    val planted = buildRecords(a)
    when (detect(readHead(input))) {
        Container.JPEG ->
            output.writeBytes(JpegEmbedder(XmpCoreEngine()).embed(input.readBytes(), planted.records, planted.note))
        Container.PNG ->
            output.writeBytes(PngEmbedder(XmpCoreEngine()).embed(input.readBytes(), planted.records, planted.note))
        Container.MP4 -> {
            when (Mp4Files.appendRecords(input, output, planted.records, planted.note)) {
                CaptionOutcome.WRITTEN -> println("mp4 caption mirrored into moov")
                CaptionOutcome.SKIPPED_UNSAFE_LAYOUT ->
                    println("note: mp4 caption skipped, layout not safely rewritable (moov not last)")
                CaptionOutcome.NOT_REQUESTED -> Unit
            }
        }
    }
    val expect = expectationFor(output, planted)
    expect.write(File(output.path + Expectation.SUFFIX))
    println("wrote ${output.path} (+${planted.records.size} record(s))")
    println("expectation sidecar: ${output.path}${Expectation.SUFFIX}")
}

private fun expectationFor(
    output: File,
    planted: Planted,
): Expectation {
    val x = extract(output)
    return Expectation(
        container = x.container.name.lowercase(),
        noteText = planted.note,
        noteId = planted.noteId,
        audioId = planted.audioId,
        audioMime = planted.audioMime,
        audioSha256 = planted.audioSha256,
        recordCount = x.records.size,
        mpfExpected = if (x.mpf?.valid == true) "valid" else "absent",
        extendedExpected = x.extendedStatus == "ok",
    )
}
