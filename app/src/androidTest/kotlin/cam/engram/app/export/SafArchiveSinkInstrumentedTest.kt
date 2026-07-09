package cam.engram.app.export

import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * On-device confidence layer (design D22): the real SAF write path in
 * [SafArchiveSink] that Kover cannot measure on the JVM. Runs against a real
 * DocumentFile-backed directory so createFile plus a ContentResolver output
 * stream and the fail-closed success signal are exercised on a device.
 *
 * A raw (file-backed) DocumentFile may adjust the display name's extension, so
 * assertions match written blobs by content, not by exact filename.
 */
@RunWith(AndroidJUnit4::class)
class SafArchiveSinkInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "saf-it-${System.nanoTime()}").apply { mkdirs() }

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun writesBlobsThroughARealDocumentTree() {
        val sink = SafArchiveSink(context, DocumentFile.fromFile(dir))

        assertTrue(
            sink.write("manifest.json", """{"archive":"engram"}""".encodeToByteArray()),
            "write must report success",
        )
        val json = dir.listFiles()?.firstOrNull { it.isFile && it.readText().contains("engram") }
        assertNotNull(json, "the json blob must be persisted to the real tree")

        assertTrue(sink.write("clip.ogg", byteArrayOf(7, 7, 7)))
        val audio = dir.listFiles()?.firstOrNull { it.isFile && it.readBytes().contentEquals(byteArrayOf(7, 7, 7)) }
        assertNotNull(audio, "the audio blob must be persisted to the real tree")
    }
}
