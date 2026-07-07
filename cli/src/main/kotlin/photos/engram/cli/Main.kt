package photos.engram.cli

import photos.engram.format.jpeg.JpegEmbedder
import photos.engram.format.mp4.Mp4Files
import photos.engram.format.png.PngCodec
import photos.engram.format.png.PngEmbedder
import photos.engram.format.records.AudioPayload
import photos.engram.format.records.EngramRecord
import photos.engram.format.records.RecordKind
import photos.engram.format.startsWith
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
            "inspect", "verify" -> {
                inspect(Args(args))
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

internal class Args(private val raw: Array<String>) {
    fun opt(name: String): String? {
        val i = raw.indexOf("--$name")
        return if (i >= 0 && i + 1 < raw.size) raw[i + 1] else null
    }

    fun required(name: String): String = opt(name) ?: throw IllegalArgumentException("missing --$name")
}

internal enum class Container { JPEG, PNG, MP4 }

internal fun detect(head: ByteArray): Container =
    when {
        head.size > 2 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> Container.JPEG
        head.size >= 8 && head.startsWith(PngCodec.SIGNATURE) -> Container.PNG
        head.size >= 12 && head.copyOfRange(4, 8).decodeToString() == "ftyp" -> Container.MP4
        else -> throw IllegalArgumentException("unrecognized container (jpeg, png, mp4 supported)")
    }

internal fun readHead(file: File): ByteArray = file.inputStream().use { it.readNBytes(16) }

private fun buildRecords(a: Args): Pair<List<EngramRecord>, String?> {
    val now = System.currentTimeMillis()
    val records = mutableListOf<EngramRecord>()
    val note = a.opt("note")
    note?.let { records += EngramRecord(RecordKind.Note, now, it.encodeToByteArray()) }
    a.opt("audio")?.let { path ->
        val f = File(path)
        require(f.isFile) { "audio file not found: $path" }
        val mime =
            a.opt("mime") ?: when (f.extension.lowercase()) {
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
