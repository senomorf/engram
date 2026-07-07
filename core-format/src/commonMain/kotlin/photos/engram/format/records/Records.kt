package photos.engram.format.records

import photos.engram.format.ByteArrayBuilder
import photos.engram.format.Crc32
import photos.engram.format.startsWith
import photos.engram.format.u16be
import photos.engram.format.u32be
import photos.engram.format.u64be
import photos.engram.format.u8

enum class RecordKind(val code: Int) {
    Note(1), Audio(2), Enrichment(3), Transcript(4);

    companion object {
        fun of(code: Int): RecordKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Wire frame, spec v0:
 * magic "EGRM" (4) | version u8 | kind u8 | flags u16be | tsMillis u64be |
 * payloadLen u32be | payload | crc32 u32be over everything before it.
 * Records are self-delimiting so a carver can recover them from damaged files.
 */
class EngramRecord(val kind: RecordKind, val tsMillis: Long, val payload: ByteArray) {

    fun encode(): ByteArray {
        val b = ByteArrayBuilder(HEADER_LEN + payload.size + 4)
        b.append(MAGIC)
        b.append(WIRE_VERSION)
        b.append(kind.code)
        b.appendU16be(0)
        b.appendU64be(tsMillis)
        b.appendU32be(payload.size.toLong())
        b.append(payload)
        val body = b.toByteArray()
        return ByteArrayBuilder(body.size + 4).append(body).appendU32be(Crc32.of(body)).toByteArray()
    }

    companion object {
        val MAGIC = "EGRM".encodeToByteArray()
        const val WIRE_VERSION = 1
        const val HEADER_LEN = 4 + 1 + 1 + 2 + 8 + 4

        fun decodeAt(bytes: ByteArray, at: Int): DecodedRecord? {
            if (!bytes.startsWith(MAGIC, at)) return null
            if (at + HEADER_LEN + 4 > bytes.size) return null
            if (bytes.u8(at + 4) != WIRE_VERSION) return null
            val kindCode = bytes.u8(at + 5)
            val ts = bytes.u64be(at + 8)
            val len = bytes.u32be(at + 16)
            if (len > Int.MAX_VALUE.toLong() || at + HEADER_LEN + len + 4 > bytes.size) return null
            val end = at + HEADER_LEN + len.toInt()
            val crcOk = bytes.u32be(end) == Crc32.of(bytes, at, end)
            val record = RecordKind.of(kindCode)?.let {
                EngramRecord(it, ts, bytes.copyOfRange(at + HEADER_LEN, end))
            }
            return DecodedRecord(record, kindCode, HEADER_LEN + len.toInt() + 4, crcOk)
        }
    }
}

class DecodedRecord(val record: EngramRecord?, val kindCode: Int, val byteLength: Int, val crcOk: Boolean)

class RecordHit(val offset: Int, val decoded: DecodedRecord)

object RecordStream {

    fun encode(records: List<EngramRecord>): ByteArray {
        val b = ByteArrayBuilder()
        records.forEach { b.append(it.encode()) }
        return b.toByteArray()
    }

    /** Strict: records must sit back to back starting at [from]. */
    fun decodeSequence(bytes: ByteArray, from: Int = 0, until: Int = bytes.size): List<RecordHit> {
        val hits = mutableListOf<RecordHit>()
        var i = from
        while (i < until) {
            val d = EngramRecord.decodeAt(bytes, i) ?: break
            hits += RecordHit(i, d)
            i += d.byteLength
        }
        return hits
    }

    /** Carve: tolerates foreign bytes between records (other vendors' trailers etc). */
    fun scan(bytes: ByteArray, from: Int = 0): List<RecordHit> {
        val hits = mutableListOf<RecordHit>()
        var i = from
        while (i + EngramRecord.HEADER_LEN <= bytes.size) {
            if (bytes.startsWith(EngramRecord.MAGIC, i)) {
                val d = EngramRecord.decodeAt(bytes, i)
                if (d != null) {
                    hits += RecordHit(i, d)
                    i += d.byteLength
                    continue
                }
            }
            i++
        }
        return hits
    }
}

object AudioPayload {

    fun encode(mime: String, data: ByteArray): ByteArray {
        val m = mime.encodeToByteArray()
        require(m.size <= 0xFFFF) { "mime too long" }
        return ByteArrayBuilder(2 + m.size + data.size).appendU16be(m.size).append(m).append(data).toByteArray()
    }

    fun decode(payload: ByteArray): Pair<String, ByteArray>? {
        if (payload.size < 2) return null
        val mlen = payload.u16be(0)
        if (2 + mlen > payload.size) return null
        val mime = payload.copyOfRange(2, 2 + mlen).decodeToString()
        return mime to payload.copyOfRange(2 + mlen, payload.size)
    }
}
