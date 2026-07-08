package photos.engram.format.records

import photos.engram.format.ByteArrayBuilder
import photos.engram.format.u16be

/**
 * Enrichment payload, spec v0.2: a small set of UTF-8 key/value fields, each
 * tagged so provenance travels with the datum. Format:
 * version u8 | fieldCount u8 | repeated [ keyLen u16be, key, valLen u16be, val ].
 * Keys are stable identifiers; readers ignore unknown keys.
 */
class EnrichmentPayload(
    val fields: Map<String, String>,
) {
    fun encode(): ByteArray {
        require(fields.size <= 0xFF) { "too many enrichment fields" }
        val b = ByteArrayBuilder()
        b.append(VERSION)
        b.append(fields.size)
        for ((k, v) in fields) {
            val kb = k.encodeToByteArray()
            val vb = v.encodeToByteArray()
            require(kb.size <= 0xFFFF && vb.size <= 0xFFFF) { "enrichment field too long" }
            b
                .appendU16be(kb.size)
                .append(kb)
                .appendU16be(vb.size)
                .append(vb)
        }
        return b.toByteArray()
    }

    companion object {
        const val VERSION = 1

        // stable field keys
        const val KEY_PLACE = "place"
        const val KEY_WEATHER = "weather"
        const val KEY_TEMP_C = "temp_c"
        const val KEY_CALENDAR = "calendar"
        const val KEY_SOURCE = "source"
        const val KEY_FETCHED_AT = "fetched_at"

        fun decode(payload: ByteArray): EnrichmentPayload? {
            if (payload.size < 2 || payload[0].toInt() != VERSION) return null
            val count = payload[1].toInt() and 0xFF
            val fields = LinkedHashMap<String, String>(count)
            var i = 2
            repeat(count) {
                if (i + 2 > payload.size) return null
                val kl = payload.u16be(i)
                i += 2
                if (i + kl > payload.size) return null
                val key = payload.copyOfRange(i, i + kl).decodeToString()
                i += kl
                if (i + 2 > payload.size) return null
                val vl = payload.u16be(i)
                i += 2
                if (i + vl > payload.size) return null
                val value = payload.copyOfRange(i, i + vl).decodeToString()
                i += vl
                fields[key] = value
            }
            return EnrichmentPayload(fields)
        }
    }
}
