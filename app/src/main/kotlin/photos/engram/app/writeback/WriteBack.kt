package photos.engram.app.writeback

import android.content.ContentResolver
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore

sealed interface WriteOutcome {
    data class Success(
        val recordCount: Int,
        val payloadLength: Long,
    ) : WriteOutcome

    data class Failed(
        val reason: String,
    ) : WriteOutcome
}

/** Payload of one save: what the user recorded for one media item. */
data class Annotation(
    val noteText: String?,
    val audioFile: java.io.File?,
    val audioMime: String = "audio/ogg",
)

/**
 * MediaStore write consent (Android 11+): modifying media the app does not own
 * needs a user-approved IntentSender, batched per save session.
 */
interface ConsentGate {
    fun consentNeeded(uris: List<String>): IntentSender?
}

class MediaStoreConsentGate(
    private val resolver: ContentResolver,
) : ConsentGate {
    override fun consentNeeded(uris: List<String>): IntentSender? =
        runCatching {
            MediaStore.createWriteRequest(resolver, uris.map(Uri::parse)).intentSender
        }.getOrNull()
}
