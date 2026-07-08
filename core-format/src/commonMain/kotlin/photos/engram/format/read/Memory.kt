package photos.engram.format.read

import photos.engram.format.records.AudioPayload
import photos.engram.format.records.EngramRecord
import photos.engram.format.records.RecordHit
import photos.engram.format.records.RecordKind

class NoteVersion(
    val text: String,
    val tsMillis: Long,
    val writer: String,
    val idHex: String,
)

class AudioClip(
    val mime: String,
    val data: ByteArray,
    val tsMillis: Long,
    val idHex: String,
)

/**
 * Reading view over a record log (spec section 1): current note is the latest
 * by timestamp, history is every note version, all audio is surfaced. Pure and
 * container-agnostic so the app and the CLI share one interpretation.
 */
class Memory(
    val noteHistory: List<NoteVersion>,
    val audio: List<AudioClip>,
    val transcripts: List<NoteVersion>,
) {
    val currentNote: NoteVersion? get() = noteHistory.maxByOrNull { it.tsMillis }

    /** Everything a full-text index should cover: note text plus transcripts. */
    fun searchableText(): String =
        (noteHistory.map { it.text } + transcripts.map { it.text })
            .filter { it.isNotBlank() }
            .joinToString("\n")

    companion object {
        fun from(hits: List<RecordHit>): Memory = fromRecords(hits.mapNotNull { it.decoded.record })

        fun fromRecords(records: List<EngramRecord>): Memory {
            val notes = mutableListOf<NoteVersion>()
            val audio = mutableListOf<AudioClip>()
            val transcripts = mutableListOf<NoteVersion>()
            for (r in records) {
                when (r.kind) {
                    RecordKind.Note ->
                        notes += NoteVersion(r.payload.decodeToString(), r.tsMillis, r.writer, r.idHex)
                    RecordKind.Transcript ->
                        transcripts += NoteVersion(r.payload.decodeToString(), r.tsMillis, r.writer, r.idHex)
                    RecordKind.Audio ->
                        AudioPayload.decode(r.payload)?.let {
                            audio += AudioClip(it.first, it.second, r.tsMillis, r.idHex)
                        }
                    RecordKind.Enrichment -> Unit
                }
            }
            return Memory(
                noteHistory = notes.sortedByDescending { it.tsMillis },
                audio = audio.sortedBy { it.tsMillis },
                transcripts = transcripts.sortedByDescending { it.tsMillis },
            )
        }
    }
}
