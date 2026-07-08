package cam.engram.app.enrich

import cam.engram.app.FakeContentAccess
import cam.engram.format.testing.SyntheticMedia
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNull

/** EXIF GPS extraction skips videos and files without a usable fix. */
@RunWith(RobolectricTestRunner::class)
class ExifGpsReaderTest {
    private val access = FakeContentAccess()
    private val reader = ExifGpsReader(access)

    @Test
    fun videosAreSkipped() {
        assertNull(reader.read("content://media/1", isVideo = true, fallbackMillis = 0))
    }

    @Test
    fun missingBytesYieldNull() {
        assertNull(reader.read("content://media/9", isVideo = false, fallbackMillis = 0))
    }

    @Test
    fun imageWithoutGpsFixYieldsNull() {
        access.files["content://media/2"] = SyntheticMedia.jpegPlain()
        assertNull(reader.read("content://media/2", isVideo = false, fallbackMillis = 123))
    }
}
