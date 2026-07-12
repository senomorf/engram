package cam.engram.format.records

import cam.engram.format.ByteArrayBuilder
import cam.engram.format.Crc32
import cam.engram.format.startsWith
import cam.engram.format.toHex
import cam.engram.format.u16be
import cam.engram.format.u32be
import cam.engram.format.u64be
import cam.engram.format.u8

enum class RecordKind(
    val code: Int,
) {
    Note(1),
    Audio(2),
    Enrichment(3),
    Transcript(4),
    ;

    companion object {
        fun of(code: Int): RecordKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Wire frame, spec v0:
 * magic "EGRM" (4) | version u8 | kind u8 | flags u16be | id (16) |
 * tsMillis u64be | writerLen u8 | writer utf8 | payloadLen u32be | payload |
 * crc32 u32be over everything before it.
 * Records are self-delimiting so a carver can recover them from damaged files.
 * The id makes every record globally addressable (export, dedup, expectations);
 * the writer id keeps a future multi-writer history reconstructable.
 */
class EngramRecord(
    val kind: RecordKind,
    val tsMillis: Long,
    val payload: ByteArray,
    val id: ByteArray = ByteArray(ID_LENGTH),
    val writer: String = "",
) {
    init {
        require(id.size == ID_LENGTH) { "record id must be $ID_LENGTH bytes" }
        require(writer.encodeToByteArray().size <= MAX_WRITER_BYTES) { "writer id too long" }
    }

    val idHex: String get() = id.toHex()

    fun encode(): ByteArray {
        val writerBytes = writer.encodeToByteArray()
        val b = ByteArrayBuilder(HEADER_LEN + writerBytes.size + payload.size + 4)
        b.append(MAGIC)
        b.append(WIRE_VERSION)
        b.append(kind.code)
        b.appendU16be(0)
        b.append(id)
        b.appendU64be(tsMillis)
        b.append(writerBytes.size)
        b.append(writerBytes)
        b.appendU32be(payload.size.toLong())
        b.append(payload)
        val body = b.toByteArray()
        return ByteArrayBuilder(body.size + 4).append(body).appendU32be(Crc32.of(body)).toByteArray()
    }

    companion object {
        val MAGIC = "EGRM".encodeToByteArray()
        const val WIRE_VERSION = 1
        const val ID_LENGTH = 16
        const val MAX_WRITER_BYTES = 255

        // fixed fields through writerLen
        private const val FIXED_LEN = 4 + 1 + 1 + 2 + ID_LENGTH + 8 + 1

        /** Header length for an empty writer, including the payload length field. */
        const val HEADER_LEN = FIXED_LEN + 4

        fun decodeAt(
            bytes: ByteArray,
            at: Int,
            limit: Int = bytes.size,
        ): DecodedRecord? {
            val end = minOf(limit, bytes.size)
            if (!bytes.startsWith(MAGIC, at)) return null
            if (at + FIXED_LEN > end) return null
            // any wire version decodes: the envelope is frozen (spec sec 10, D27), so
            // future-version frames surface opaque instead of vanishing mid-stream
            val version = bytes.u8(at + 4)
            val kindCode = bytes.u8(at + 5)
            val id = bytes.copyOfRange(at + 8, at + 24)
            val ts = bytes.u64be(at + 24)
            val writerLen = bytes.u8(at + 32)
            val writerEnd = at + FIXED_LEN + writerLen
            if (writerEnd + 4 > end) return null
            val payloadLen = bytes.u32be(writerEnd)
            // length math stays in Long: a hostile payloadLen must not wrap the bounds check
            val payloadEndLong = writerEnd + 4 + payloadLen
            if (payloadEndLong + 4 > end) return null
            val payloadEnd = payloadEndLong.toInt()
            val crcOk = bytes.u32be(payloadEnd) == Crc32.of(bytes, at, payloadEnd)
            val record =
                if (version == WIRE_VERSION) {
                    RecordKind.of(kindCode)?.let { kind ->
                        // decode must never throw on a hostile or corrupt frame: a writer that
                        // does not round-trip (invalid utf-8 re-encoding past the 255-byte limit)
                        // surfaces as an opaque frame so the carver keeps scanning past it
                        runCatching {
                            EngramRecord(
                                kind = kind,
                                tsMillis = ts,
                                payload = bytes.copyOfRange(writerEnd + 4, payloadEnd),
                                id = id,
                                writer = bytes.copyOfRange(at + FIXED_LEN, writerEnd).decodeToString(),
                            )
                        }.getOrNull()
                    }
                } else {
                    null
                }
            return DecodedRecord(record, kindCode, payloadEnd + 4 - at, crcOk, version)
        }
    }
}

class DecodedRecord(
    val record: EngramRecord?,
    val kindCode: Int,
    val byteLength: Int,
    val crcOk: Boolean,
    val version: Int = EngramRecord.WIRE_VERSION,
)

class RecordHit(
    val offset: Int,
    val decoded: DecodedRecord,
)

object RecordStream {
    fun encode(records: List<EngramRecord>): ByteArray {
        val b = ByteArrayBuilder()
        records.forEach { b.append(it.encode()) }
        return b.toByteArray()
    }

    /**
     * Strict: records must sit back to back starting at [from] and end within [until].
     * After the first damaged frame the rest of the region degrades to a byte-wise
     * carve ([scan]), whether the frame decoded CRC-bad or did not decode at all
     * (damaged magic, truncated header, length claim past the region): a damaged
     * head can never hide the intact frames behind it.
     */
    fun decodeSequence(
        bytes: ByteArray,
        from: Int = 0,
        until: Int = bytes.size,
    ): List<RecordHit> {
        val hits = mutableListOf<RecordHit>()
        var i = from
        while (i < until) {
            val d = EngramRecord.decodeAt(bytes, i, until)
            if (d == null) {
                // an undecodable header has no span authority either; there is no
                // hit to surface, so carve from here for whatever survived
                hits += scan(bytes, i, until)
                return hits
            }
            hits += RecordHit(i, d)
            if (!d.crcOk) {
                hits += scan(bytes, i + 1, until)
                return hits
            }
            i += d.byteLength
        }
        return hits
    }

    /** Carve: tolerates foreign bytes between records (other vendors' trailers etc). */
    fun scan(
        bytes: ByteArray,
        from: Int = 0,
        until: Int = bytes.size,
    ): List<RecordHit> {
        val hits = mutableListOf<RecordHit>()
        var i = from
        while (i + EngramRecord.HEADER_LEN <= until) {
            if (bytes.startsWith(EngramRecord.MAGIC, i)) {
                val d = EngramRecord.decodeAt(bytes, i, until)
                if (d != null && d.crcOk) {
                    hits += RecordHit(i, d)
                    i += d.byteLength
                    continue
                }
                // the crc covers the length field, so a crc-bad frame has no span
                // authority regardless of version: surface ours as a damaged hit
                // (classify/verify must see it), drop a foreign-version candidate as
                // noise (D27), and resync byte-wise either way so an inflated length
                // cannot swallow intact frames behind it
                if (d != null && d.version == EngramRecord.WIRE_VERSION) {
                    hits += RecordHit(i, d)
                }
            }
            i++
        }
        return hits
    }
}

object AudioPayload {
    fun encode(
        mime: String,
        data: ByteArray,
    ): ByteArray {
        val m = mime.encodeToByteArray()
        require(m.size <= 0xFFFF) { "mime too long" }
        return ByteArrayBuilder(2 + m.size + data.size)
            .appendU16be(m.size)
            .append(m)
            .append(data)
            .toByteArray()
    }

    fun decode(payload: ByteArray): Pair<String, ByteArray>? {
        if (payload.size < 2) return null
        val mlen = payload.u16be(0)
        if (2 + mlen > payload.size) return null
        val mime = payload.copyOfRange(2, 2 + mlen).decodeToString()
        return mime to payload.copyOfRange(2 + mlen, payload.size)
    }
}
