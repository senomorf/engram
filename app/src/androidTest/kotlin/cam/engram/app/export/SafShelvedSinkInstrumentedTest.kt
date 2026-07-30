package cam.engram.app.export

import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * On-device confidence layer (design D22) for [SafShelvedSink], the SAF path that copies
 * shelved backups out (issue #92). Kover cannot measure it, and what it moves is the only
 * remaining copy of a photo displaced by a reused media id, so the real DocumentFile plus
 * ContentResolver stream is exercised on a device rather than trusted.
 *
 * Unlike [SafArchiveSink] this sink hands back a stream instead of taking bytes, so a
 * full-size video is never read whole; these tests write through that stream.
 *
 * A raw (file-backed) DocumentFile may adjust the display name's extension, so assertions
 * match written blobs by content, not by exact filename.
 */
@RunWith(AndroidJUnit4::class)
class SafShelvedSinkInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "saf-shelved-it-${System.nanoTime()}").apply { mkdirs() }

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun streamsAShelvedBackupIntoARealDocumentTree() {
        val sink = SafShelvedSink(context, DocumentFile.fromFile(dir))
        val payload = ByteArray(4096) { (it % 251).toByte() }

        val out = sink.open("engram-recovered-40.100.0.jpg", "image/jpeg")
        assertNotNull(out, "the sink must open a stream for a shelved backup")
        out.use { it.write(payload) }

        val landed = dir.listFiles()?.firstOrNull { it.isFile && it.readBytes().contentEquals(payload) }
        assertNotNull(landed, "the shelved bytes must reach the real tree intact")
    }

    @Test
    fun asecondSaveNeverOverwritesTheFirstRescuedCopy() {
        val sink = SafShelvedSink(context, DocumentFile.fromFile(dir))
        val first = ByteArray(64) { 1 }

        sink.open("engram-recovered-40.100.0.jpg", "image/jpeg")?.use { it.write(first) }
        sink.open("engram-recovered-41.200.0.jpg", "image/jpeg")?.use { it.write(byteArrayOf(2, 2)) }

        // each rescued copy is irreplaceable, so saving a second must not disturb the first
        val landed = dir.listFiles().orEmpty().filter { it.isFile }
        assertTrue(
            landed.any { it.readBytes().contentEquals(first) },
            "the first rescued copy must survive a later save: ${landed.map { it.name }}",
        )
        assertTrue(landed.any { it.readBytes().contentEquals(byteArrayOf(2, 2)) }, "the second copy lands too")
    }

    @Test
    fun openReturnsNullWhenTheTreeCannotBeWritten() {
        // a DocumentFile over a path that is not a directory cannot create children
        val notADir = File(dir, "plain-file").apply { writeText("x") }
        val sink = SafShelvedSink(context, DocumentFile.fromFile(notADir))

        assertNull(
            sink.open("engram-recovered-40.100.0.jpg", "image/jpeg"),
            "an unwritable tree must fail closed, not throw",
        )
    }
}
