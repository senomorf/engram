package cam.engram.app.data.media

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
    val dateModified: Long,
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
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE,
            )
        val paths = mutableListOf("DCIM/Camera/%")
        if (includeScreenshots) {
            paths += "Pictures/Screenshots/%"
            paths += "DCIM/Screenshots/%"
        }
        val args = paths.toMutableList()
        val pathClause = paths.joinToString(" OR ") { "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" }
        // v1 only writes JPEG and PNG (review F10); other image types (e.g. HEIC)
        // must not reach the JPEG embedder, so they are excluded at the source
        val selection =
            if (isVideo) {
                "($pathClause)"
            } else {
                args += SUPPORTED_IMAGE_MIME
                "($pathClause) AND ${MediaStore.MediaColumns.MIME_TYPE} IN ($MIME_PLACEHOLDERS)"
            }
        val out = mutableListOf<SourceItem>()
        resolver
            .query(collection, projection, selection, args.toTypedArray(), null)
            ?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val rel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val taken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val added = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
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
                            dateModified = c.getLong(modified),
                        )
                }
            }
        return out
    }

    private companion object {
        val SUPPORTED_IMAGE_MIME = arrayOf("image/jpeg", "image/png")
        val MIME_PLACEHOLDERS = SUPPORTED_IMAGE_MIME.joinToString(",") { "?" }
    }
}
