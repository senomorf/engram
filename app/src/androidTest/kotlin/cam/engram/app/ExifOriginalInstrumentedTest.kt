package cam.engram.app

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import cam.engram.app.data.media.ResolverContentAccess
import cam.engram.format.testing.SyntheticMedia
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * On-device GPS-preservation check (finding 1, design D22). Proves the
 * setRequireOriginal read path integrates on a real device and preserves EXIF GPS
 * through the read the backup uses. Faithful redaction is only observable with
 * camera-owned media in manual device-QA: media the test app inserts itself is never
 * redacted, so this is a regression guard, not a redaction proof. Gradle Managed Device.
 */
@RunWith(AndroidJUnit4::class)
class ExifOriginalInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private var inserted: Uri? = null

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.ACCESS_MEDIA_LOCATION,
        )

    @After
    fun cleanup() {
        inserted?.let { resolver.delete(it, null, null) }
    }

    @Test
    fun requireOriginalReadPreservesGps() {
        val lat = 37.4219983
        val lon = -122.084
        val gpsJpeg = jpegWithGps(lat, lon)

        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "engram-gps-${System.nanoTime()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
        val uri = assertNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        inserted = uri
        resolver.openOutputStream(uri)!!.use { it.write(gpsJpeg) }

        val access = ResolverContentAccess(resolver, requireOriginal = { true })
        val readBack = assertNotNull(access.readBytes(uri.toString()), "requireOriginal read must return bytes")

        val exif = ExifInterface(ByteArrayInputStream(readBack))
        val latLong = assertNotNull(exif.latLong, "GPS must survive the original read")
        assertTrue(abs(latLong[0] - lat) < 0.001, "latitude preserved")
        assertTrue(abs(latLong[1] - lon) < 0.001, "longitude preserved")
    }

    // ExifInterface writes GPS into a valid baseline JPEG on a temp file, then we read
    // the tagged bytes back out to seed MediaStore
    private fun jpegWithGps(
        lat: Double,
        lon: Double,
    ): ByteArray {
        val tmp = File.createTempFile("engram-gps", ".jpg", context.cacheDir)
        return try {
            tmp.writeBytes(SyntheticMedia.jpegPlain())
            ExifInterface(tmp.absolutePath).apply {
                setLatLong(lat, lon)
                saveAttributes()
            }
            tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }
}
