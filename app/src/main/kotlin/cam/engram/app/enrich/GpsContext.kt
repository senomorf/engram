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
 * Reads the GPS fix and capture time the camera already stamped into EXIF
 * (design: enrichment derives from EXIF, so the app never needs the location
 * permission). Only images carry usable EXIF here; videos are skipped.
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
