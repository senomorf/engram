package cam.engram.app.enrich

import androidx.exifinterface.media.ExifInterface
import cam.engram.app.data.media.ContentAccess
import java.io.ByteArrayInputStream

data class GpsContext(
    val lat: Double,
    val lon: Double,
    val timestampMillis: Long,
)

/**
 * Reads the GPS fix and capture time the camera already stamped into EXIF. The app holds
 * ACCESS_MEDIA_LOCATION and reads original (unredacted) bytes, so this EXIF GPS is present
 * rather than stripped by scoped storage (finding 1). Only images carry usable EXIF here;
 * videos are skipped.
 */
class ExifGpsReader(
    private val access: ContentAccess,
) {
    fun read(
        uri: String,
        isVideo: Boolean,
        fallbackMillis: Long,
    ): GpsContext? {
        if (isVideo) return null
        val bytes = access.readBytes(uri) ?: return null
        val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val latLong = exif.latLong ?: return null
        // capture time comes from MediaStore DATE_TAKEN (fallbackMillis), which is
        // as reliable as EXIF here and avoids the restricted dateTimeOriginal getter
        return GpsContext(latLong[0], latLong[1], fallbackMillis)
    }
}
