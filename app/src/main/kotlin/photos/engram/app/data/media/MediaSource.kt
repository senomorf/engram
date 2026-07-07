package photos.engram.app.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore

data class SourceItem(
    val mediaId: Long,
    val uri: String,
    val isVideo: Boolean,
    val mime: String,
    val relativePath: String,
    val takenAtMillis: Long,
    val sizeBytes: Long,
)

interface MediaSource {
    suspend fun snapshot(includeScreenshots: Boolean): List<SourceItem>
}

/** Camera buckets plus (optionally) Screenshots, per design D11. */
class MediaStoreSource(
    private val resolver: ContentResolver,
) : MediaSource {
    override suspend fun snapshot(includeScreenshots: Boolean): List<SourceItem> =
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false, includeScreenshots) +
            query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true, includeScreenshots = false)

    private fun query(
        collection: android.net.Uri,
        isVideo: Boolean,
        includeScreenshots: Boolean,
    ): List<SourceItem> {
        val projection =
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.SIZE,
            )
        val paths = mutableListOf("DCIM/Camera/%")
        if (includeScreenshots) {
            paths += "Pictures/Screenshots/%"
            paths += "DCIM/Screenshots/%"
        }
        val selection = paths.joinToString(" OR ") { "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" }
        val out = mutableListOf<SourceItem>()
        resolver
            .query(collection, projection, selection, paths.toTypedArray(), null)
            ?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val rel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val taken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val added = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (c.moveToNext()) {
                    val mediaId = c.getLong(id)
                    val takenAt = if (c.isNull(taken)) c.getLong(added) * 1000 else c.getLong(taken)
                    out +=
                        SourceItem(
                            mediaId = mediaId,
                            uri = ContentUris.withAppendedId(collection, mediaId).toString(),
                            isVideo = isVideo,
                            mime = c.getString(mime) ?: if (isVideo) "video/mp4" else "image/jpeg",
                            relativePath = c.getString(rel).orEmpty(),
                            takenAtMillis = takenAt,
                            sizeBytes = c.getLong(size),
                        )
                }
            }
        return out
    }
}
