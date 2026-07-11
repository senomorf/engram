package cam.engram.cli

import cam.engram.format.records.AudioPayload
import java.io.File

internal const val EXIT_DEGRADED = 3
internal const val EXIT_DAMAGED = 4

private class Check(
    val name: String,
    val status: String,
) {
    val damaged get() = status == "gone" || status == "corrupted" || status.startsWith("broken")
    val degraded get() = status == "degraded"
}

internal fun verify(a: Args): Int {
    val file = File(a.required("in"))
    require(file.isFile) { "input not found: ${file.path}" }
    val expectFile = a.opt("expect")?.let { File(it) } ?: File(file.path + Expectation.SUFFIX).takeIf { it.isFile }
    val x = extract(file)
    val checks = mutableListOf<Check>()
    val expect = expectFile?.let { Expectation.read(it) }
    if (expect != null) {
        expect.noteId?.let { checks += Check("note", noteStatus(x, expect)) }
        expect.audioId?.let { checks += Check("audio", audioStatus(x, expect)) }
        if (expect.mpfExpected == "valid") {
            checks += Check("mpf", if (x.mpf?.valid == true) "exact" else "broken: ${x.mpf?.problems ?: "mpf absent"}")
        }
        if (expect.extendedExpected) {
            checks += Check("extendedXmp", if (x.extendedStatus == "ok") "exact" else "broken: ${x.extendedStatus}")
        }
    }
    if (x.extendedStatus.startsWith("broken")) checks += Check("extendedXmpStructure", x.extendedStatus)
    checks += integrityChecks(x, expect)
    val verdict =
        when {
            expect == null -> "unverified"
            checks.any { it.damaged } -> "damaged"
            checks.any { it.degraded } -> "degraded"
            else -> "intact"
        }
    if (a.flag("json")) println(toJson(file, x, checks, verdict)) else printHuman(file, x, checks, verdict)
    return when (verdict) {
        "damaged" -> EXIT_DAMAGED
        "degraded" -> EXIT_DEGRADED
        else -> 0
    }
}

// damage the planted-item checks cannot see: a damaged carrier, a stray crc-bad
// fragment, a historical record lost. Appends stay legal: only losses count. The
// planted note/audio keep their dedicated ladders (mirror fallbacks can judge
// them degraded, not gone), so only ids without such a ladder are judged by bare
// presence, and the raw count comparison serves legacy sidecars that lack ids.
private fun integrityChecks(
    x: Extraction,
    expect: Expectation?,
): List<Check> {
    val checks = mutableListOf<Check>()
    if (x.integrity == "damaged") checks += Check("carrier", "degraded")
    if (x.records.any { !it.crcOk }) checks += Check("recordIntegrity", "degraded")
    if (expect == null) return checks
    val okIds = x.records.filter { it.crcOk }.map { it.idHex }
    if (expect.ids.isEmpty() && okIds.size < expect.recordCount) {
        checks +=
            Check("recordCount", "broken: expected ${expect.recordCount} crc-valid record(s), found ${okIds.size}")
    }
    val dedicated = setOfNotNull(expect.noteId, expect.audioId)
    val missing = expect.ids.filterNot { it in okIds || it in dedicated }
    if (missing.isNotEmpty()) checks += Check("untrackedRecords", "broken: missing ${missing.joinToString(",")}")
    return checks
}

private fun noteStatus(
    x: Extraction,
    expect: Expectation,
): String {
    val rec = x.records.firstOrNull { it.idHex == expect.noteId && it.kind == "Note" }
    val text = expect.noteText
    return when {
        rec != null && !rec.crcOk -> "corrupted"
        rec != null && text != null && rec.payload?.decodeToString() == text -> "exact"
        rec != null -> "corrupted"
        text != null &&
            (
                x.xmpSummary?.description == text ||
                    x.iptcCaption == text ||
                    x.mp4Caption == text
            ) -> "degraded"
        else -> "gone"
    }
}

private fun audioStatus(
    x: Extraction,
    expect: Expectation,
): String {
    val rec = x.records.firstOrNull { it.idHex == expect.audioId && it.kind == "Audio" } ?: return "gone"
    if (!rec.crcOk) return "corrupted"
    val decoded = rec.payload?.let { AudioPayload.decode(it) } ?: return "corrupted"
    if (expect.audioMime != null && decoded.first != expect.audioMime) return "corrupted"
    if (expect.audioSha256 != null && sha256Hex(decoded.second) != expect.audioSha256) return "corrupted"
    return "exact"
}

private fun printHuman(
    file: File,
    x: Extraction,
    checks: List<Check>,
    verdict: String,
) {
    println("verify: ${file.path}")
    println("container: ${x.container.name.lowercase()}")
    println("integrity: ${x.integrity}")
    println("records: ${x.records.size} (${x.records.count { it.crcOk }} crc ok)")
    x.xmpSummary?.let {
        println("xmp: description=${it.description ?: "(none)"} engram=${it.hasEngram}")
    } ?: println("xmp: absent")
    x.iptcCaption?.let { println("iptc caption: $it") }
    x.mp4Caption?.let { println("mp4 caption: $it") }
    if (x.extendedStatus != "absent") println("extended xmp: ${x.extendedStatus}")
    x.mpf?.let { println("mpf: ${if (it.present) (if (it.valid) "valid" else "BROKEN ${it.problems}") else "absent"}") }
    if (x.motionMarkers) println("motion photo markers present")
    checks.forEach { println("check ${it.name}: ${it.status}") }
    println("verdict: $verdict")
}

private fun toJson(
    file: File,
    x: Extraction,
    checks: List<Check>,
    verdict: String,
): String {
    val records =
        x.records.joinToString(",") {
            """{"kind":${js(
                it.kind,
            )},"id":${js(it.idHex)},"writer":${js(it.writer)},"ts":${it.tsMillis},"crcOk":${it.crcOk}}"""
        }
    val checksJson = checks.joinToString(",") { """{"name":${js(it.name)},"status":${js(it.status)}}""" }
    val xmp =
        x.xmpSummary?.let {
            """{"present":true,"engram":${it.hasEngram},"description":${js(it.description)},""" +
                """"payloadLength":${it.payloadLength ?: 0},"recordCount":${it.recordCount ?: 0},""" +
                """"extendedGuid":${js(it.extendedXmpGuid)}}"""
        } ?: """{"present":false}"""
    val mpf =
        x.mpf?.let {
            """{"present":${it.present},"valid":${it.valid},"problems":[${it.problems.joinToString(
                ",",
            ) { p -> js(p) }}]}"""
        } ?: """{"present":false}"""
    return """{"file":${js(file.path)},"container":${js(x.container.name.lowercase())},""" +
        """"integrity":${js(x.integrity)},"verdict":${js(verdict)},""" +
        """"records":[$records],"checks":[$checksJson],"xmp":$xmp,"mpf":$mpf,""" +
        """"extendedXmp":${js(x.extendedStatus)},"motionMarkers":${x.motionMarkers},""" +
        """"iptcCaption":${js(x.iptcCaption)},"mp4Caption":${js(x.mp4Caption)}}"""
}

private fun js(s: String?): String {
    if (s == null) return "null"
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.append('"').toString()
}
