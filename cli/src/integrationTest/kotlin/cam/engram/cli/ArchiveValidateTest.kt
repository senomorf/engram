package cam.engram.cli

import cam.engram.format.archive.ArchiveReader
import cam.engram.format.archive.EngramArchive
import cam.engram.format.testing.SyntheticMedia
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The validator must prove the inventory complete in both directions, reject
 * duplicate and path-escaping names, and require the archive marker (spec sec 11):
 * a manifest that inventories nothing must not vouch for a populated archive.
 */
class ArchiveValidateTest {
    private val dir = createTempDirectory("engram-validate").toFile()

    private fun run(vararg args: String): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer, true))
        val code =
            try {
                cliMain(arrayOf(*args))
            } finally {
                System.setOut(original)
            }
        return code to buffer.toString()
    }

    private fun freshArchive(tag: String): File {
        val src = File(dir, "$tag.jpg").apply { writeBytes(SyntheticMedia.jpegPlain()) }
        val out = File(dir, "$tag-out.jpg")
        check(cliMain(arrayOf("generate", "--in", src.path, "--out", out.path, "--note", "memory $tag")) == 0)
        val archiveDir = File(dir, "$tag-archive")
        check(cliMain(arrayOf("archive", "--in", out.path, "--out", archiveDir.path)) == 0)
        return archiveDir
    }

    private fun rewriteManifest(
        archiveDir: File,
        transform: (ArchiveReader.Manifest) -> String,
    ) {
        val f = File(archiveDir, "manifest.json")
        f.writeText(transform(ArchiveReader.parseManifest(f.readText())))
    }

    @Test
    fun filesEmptiedOnPopulatedArchiveIsInvalid() {
        val archive = freshArchive("empty-inv")
        // itemCount still matches the item documents, so only the inventory check can catch this
        rewriteManifest(archive) { m -> EngramArchive.manifest(m.itemCount) }
        val (code, output) = run("archive", "validate", "--in", archive.path)
        assertEquals(1, code, output)
        assertTrue(output.contains("not inventoried"), output)
    }

    @Test
    fun extraOnDiskFileIsInvalid() {
        val archive = freshArchive("stray")
        File(archive, "stray.bin").writeBytes(byteArrayOf(1, 2, 3))
        val (code, output) = run("archive", "validate", "--in", archive.path)
        assertEquals(1, code, output)
        assertTrue(output.contains("stray.bin"), output)
    }

    @Test
    fun itemReferencingUninventoriedLogIsInvalid() {
        val archive = freshArchive("no-log-entry")
        rewriteManifest(archive) { m ->
            EngramArchive.manifest(m.itemCount, m.files.filterNot { it.name.endsWith(".records") })
        }
        val (code, output) = run("archive", "validate", "--in", archive.path)
        assertEquals(1, code, output)
        assertTrue(output.contains(".records"), output)
    }

    @Test
    fun duplicateInventoryEntryIsInvalid() {
        val archive = freshArchive("dup")
        rewriteManifest(archive) { m -> EngramArchive.manifest(m.itemCount, m.files + m.files.first()) }
        val (code, output) = run("archive", "validate", "--in", archive.path)
        assertEquals(1, code, output)
        assertTrue(output.contains("duplicate"), output)
    }

    @Test
    fun pathEscapingNameIsInvalid() {
        val archive = freshArchive("escape")
        // a real matching file outside the archive directory: only a name guard can
        // stop the validator from vouching for content it never should have read
        val evil = File(archive.parentFile, "evil").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        rewriteManifest(archive) { m ->
            EngramArchive.manifest(
                m.itemCount,
                m.files + EngramArchive.ManifestFile("../evil", EngramArchive.contentHashName(evil.readBytes())),
            )
        }
        val (code, output) = run("archive", "validate", "--in", archive.path)
        assertEquals(1, code, output)
        assertTrue(output.contains("unsafe"), output)
    }

    @Test
    fun wrongArchiveMarkerIsInvalid() {
        val archive = freshArchive("marker")
        val f = File(archive, "manifest.json")
        f.writeText(f.readText().replace("\"archive\":\"engram\"", "\"archive\":\"imposter\""))
        val (code, output) = run("archive", "validate", "--in", archive.path)
        assertEquals(1, code, output)
        assertTrue(output.contains("marker"), output)
    }

    @Test
    fun legacyV2ArchiveStillValidates() {
        val legacy = File(dir, "legacy-archive").apply { mkdirs() }
        File(legacy, "manifest.json")
            .writeText("""{"archive":"engram","manifestVersion":2,"itemCount":0,"files":[]}""")
        val (code, output) = run("archive", "validate", "--in", legacy.path)
        assertEquals(0, code, output)
        assertTrue(output.contains("archive valid"), output)
    }
}
