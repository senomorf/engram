package cam.engram.app.writeback

import cam.engram.format.records.AudioPayload
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import java.security.MessageDigest

/** Builds wire records from a user annotation: content-addressed ids, app writer id. */
class RecordFactory(
    private val writerId: String = "engram-android",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun fromAnnotation(
        annotation: Annotation,
        mediaId: Long,
    ): List<EngramRecord> {
        val now = clock()
        val records = mutableListOf<EngramRecord>()
        val draftStamp = annotation.createdAtMillis
        annotation.noteText?.takeIf { it.isNotBlank() }?.let {
            val payload = it.encodeToByteArray()
            val id = idFor(mediaId, RecordKind.Note, payload, draftStamp)
            records += EngramRecord(RecordKind.Note, now, payload, id, writerId)
        }
        annotation.audioFile?.takeIf { it.exists() && it.length() > 0 }?.let { f ->
            val payload = AudioPayload.encode(annotation.audioMime, f.readBytes())
            val id = idFor(mediaId, RecordKind.Audio, payload, draftStamp)
            records += EngramRecord(RecordKind.Audio, now, payload, id, writerId)
        }
        return records
    }

    // content-addressed id: the same draft saved again on the same capture always derives the same
    // id, so a save retried after a crash (the draft still present) re-derives the id write-back
    // already landed and the write recognizes and skips it, never appending a duplicate to the
    // append-only file (reviewer D). The draft's own timestamp is part of the hash (review N3):
    // without it, re-annotating with text the file already carries derived an id already present
    // and the new version was silently dropped. 16 bytes of sha-256 over the capture id, the
    // draft stamp, the kind, and the payload.
    private fun idFor(
        mediaId: Long,
        kind: RecordKind,
        payload: ByteArray,
        draftStamp: Long,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteArray(8) { i -> (mediaId shr (56 - i * 8)).toByte() })
        digest.update(ByteArray(8) { i -> (draftStamp shr (56 - i * 8)).toByte() })
        digest.update(kind.code.toByte())
        digest.update(payload)
        return digest.digest().copyOf(EngramRecord.ID_LENGTH)
    }
}
