package cam.engram.app

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cam.engram.app.data.media.MediaStoreSource
import cam.engram.app.data.media.ResolverContentAccess
import cam.engram.app.data.media.WriteResult
import cam.engram.format.testing.SyntheticMedia
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * On-device confidence layer (design D22): the real platform adapters that Kover
 * cannot measure on the JVM (ResolverContentAccess, MediaStoreSource) run here
 * against a real ContentResolver and MediaStore. Runs on a Gradle Managed Device.
 */
@RunWith(AndroidJUnit4::class)
class PlatformAdaptersInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private var inserted: Uri? = null

    @After
    fun cleanup() {
        inserted?.let { resolver.delete(it, null, null) }
    }

    @Test
    fun resolverContentAccessWritesAndReadsThroughRealResolver() {
        // an app-owned MediaStore image needs no user consent under scoped storage
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "engram-it-${System.nanoTime()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
        val uri = assertNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        inserted = uri
        val access = ResolverContentAccess(resolver)
        val bytes = SyntheticMedia.jpegPlain()
        assertEquals(WriteResult.Ok, access.writeBytes(uri.toString(), bytes), "real resolver write must succeed")
        assertContentEquals(bytes, access.readBytes(uri.toString()))
    }

    @Test
    fun mediaStoreSourceSnapshotQueriesRealProvider() {
        // block body (not = runBlocking): a JUnit4 @Test method must return void
        runBlocking {
            // exercises the real MediaStore projection/paging path; the store may be empty
            val items = MediaStoreSource(resolver).snapshot(includeScreenshots = true)
            assertNotNull(items)
        }
    }
}
