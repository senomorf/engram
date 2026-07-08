package cam.engram.app.writeback

import cam.engram.format.records.AudioPayload
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import java.security.SecureRandom

/** Builds wire records from a user annotation: fresh ids, app writer id. */
class RecordFactory(
    private val writerId: String = "engram-android",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()

    fun fromAnnotation(annotation: Annotation): List<EngramRecord> {
        val now = clock()
        val records = mutableListOf<EngramRecord>()
        annotation.noteText?.takeIf { it.isNotBlank() }?.let {
            records += EngramRecord(RecordKind.Note, now, it.encodeToByteArray(), newId(), writerId)
        }
        annotation.audioFile?.takeIf { it.exists() && it.length() > 0 }?.let { f ->
            records +=
                EngramRecord(
                    RecordKind.Audio,
                    now,
                    AudioPayload.encode(annotation.audioMime, f.readBytes()),
                    newId(),
                    writerId,
                )
        }
        return records
    }

    private fun newId(): ByteArray = ByteArray(EngramRecord.ID_LENGTH).also { random.nextBytes(it) }
}
