package cam.engram.app.verify

import cam.engram.app.data.media.ContentAccess
import cam.engram.format.read.CarrierIntegrity
import cam.engram.format.read.ContainerExtraction
import cam.engram.format.read.ContainerType
import cam.engram.format.read.Extraction
import cam.engram.format.read.ExtractionFiles
import cam.engram.format.read.Memory
import cam.engram.format.read.Survival
import cam.engram.format.xmp.XmpCoreEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class VerifyReport(
    val recordCount: Int,
    val corruptCount: Int,
    val hasNote: Boolean,
    val audioClips: Int,
    val captionVisible: Boolean,
    val mpfIntact: Boolean?,
    val summary: Survival,
)

/**
 * In-app backup verifier (design D14): the user picks a file that round-tripped
 * a cloud or messenger, and this reports what survived, turning the
 * survivability story into something the user can see. Reading and
 * classification live in core-format (ContainerExtraction); this adapter only
 * wires the ContentAccess seam and shapes the report.
 */
class BackupVerifier(
    private val access: ContentAccess,
) {
    suspend fun verify(uri: String): VerifyReport =
        withContext(Dispatchers.IO) {
            report(inspect(uri))
        }

    private fun inspect(uri: String): Extraction? {
        val head =
            access.withChannel(uri) { ch ->
                val buf = ByteBuffer.allocate(16)
                while (buf.hasRemaining() && ch.read(buf) > 0) {
                    // keep filling: a short read is not end of stream
                }
                buf.array().copyOf(buf.position())
            } ?: return null
        return when (ContainerExtraction.detect(head)) {
            // videos stream through the channel so verifying one never loads it whole
            ContainerType.MP4 -> access.withChannel(uri) { ExtractionFiles.inspectMp4(it) }
            ContainerType.JPEG, ContainerType.PNG ->
                access.readBytes(uri)?.let { ContainerExtraction.inspect(it, XmpCoreEngine()) }
            null -> null
        }
    }

    private fun report(x: Extraction?): VerifyReport {
        val all = x?.records.orEmpty()
        val valid = all.count { it.crcOk }
        // a damaged carrier lost frames beyond the CRC-bad ones: exact for png
        // (undecodable chunks), at least one for an undecodable engram-box tail
        val carrierLost =
            if (x != null && x.integrity is CarrierIntegrity.CarrierDamaged) {
                if (x.container == ContainerType.PNG) (x.pngEngramChunks - all.size).coerceAtLeast(1) else 1
            } else {
                0
            }
        val memory = Memory.fromRecords(all.mapNotNull { d -> d.record.takeIf { d.crcOk } })
        val caption =
            when (x?.container) {
                ContainerType.MP4 -> x.mp4Caption
                ContainerType.JPEG, ContainerType.PNG -> x.xmpSummary?.description
                null -> null
            }
        val captionVisible = !caption.isNullOrBlank()
        return VerifyReport(
            recordCount = valid,
            corruptCount = (all.size - valid) + carrierLost,
            hasNote = memory.currentNote != null,
            audioClips = memory.audio.size,
            captionVisible = captionVisible,
            mpfIntact = x?.mpf?.takeIf { it.present }?.valid,
            summary = ContainerExtraction.classify(x, captionVisible),
        )
    }
}
