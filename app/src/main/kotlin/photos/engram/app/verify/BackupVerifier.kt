package photos.engram.app.verify

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import photos.engram.format.jpeg.JpegCodec
import photos.engram.format.jpeg.MpfInspector
import photos.engram.format.jpeg.Segment
import photos.engram.format.jpeg.isXmpApp1
import photos.engram.format.jpeg.xmpPacket
import photos.engram.format.mp4.Mp4Codec
import photos.engram.format.png.PngCodec
import photos.engram.format.read.Memory
import photos.engram.format.records.RecordStream
import photos.engram.format.startsWith
import photos.engram.format.xmp.XmpCoreEngine

class VerifyReport(
    val recordCount: Int,
    val hasNote: Boolean,
    val audioClips: Int,
    val captionVisible: Boolean,
    val mpfIntact: Boolean?,
    val summary: Survival,
)

enum class Survival { FULL, CAPTION_ONLY, GONE, UNREADABLE }

/**
 * In-app backup verifier (design D14): the user picks a file that round-tripped
 * a cloud or messenger, and this reports what survived, turning the
 * survivability story into something the family can see.
 */
class BackupVerifier(
    private val context: Context,
) {
    suspend fun verify(uri: Uri): VerifyReport =
        withContext(Dispatchers.IO) {
            val bytes =
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                    ?: return@withContext VerifyReport(0, false, 0, false, null, Survival.UNREADABLE)
            when {
                bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> verifyJpeg(bytes)
                bytes.startsWith(PngCodec.SIGNATURE) -> verifyPng(bytes)
                bytes.size >= 12 && bytes.copyOfRange(4, 8).decodeToString() == "ftyp" -> verifyMp4(bytes)
                else -> VerifyReport(0, false, 0, false, null, Survival.UNREADABLE)
            }
        }

    private fun verifyJpeg(bytes: ByteArray): VerifyReport {
        val records = RecordStream.scan(bytes).filter { it.decoded.crcOk }
        val memory = Memory.from(records)
        val caption =
            runCatching {
                JpegCodec
                    .parse(bytes)
                    .filterIsInstance<Segment>()
                    .firstOrNull { it.isXmpApp1() }
                    ?.let { XmpCoreEngine().read(it.xmpPacket()).description }
            }.getOrNull()
        val mpf = runCatching { MpfInspector.inspect(bytes) }.getOrNull()
        return report(records.size, memory, caption, mpf?.takeIf { it.present }?.valid)
    }

    private fun verifyPng(bytes: ByteArray): VerifyReport {
        val file =
            runCatching { PngCodec.parse(bytes) }.getOrNull()
                ?: return VerifyReport(0, false, 0, false, null, Survival.UNREADABLE)
        val records = PngCodec.engramRecords(file)
        val memory = Memory.fromRecords(records.mapNotNull { it.record })
        val caption =
            file.chunks
                .firstNotNullOfOrNull {
                    PngCodec.xmpPacket(
                        it,
                    )
                }?.let { XmpCoreEngine().read(it).description }
        return report(records.size, memory, caption, null)
    }

    private fun verifyMp4(bytes: ByteArray): VerifyReport {
        val records = runCatching { Mp4Codec.readRecords(bytes) }.getOrDefault(emptyList())
        val memory = Memory.from(records)
        val caption =
            runCatching {
                photos.engram.format.mp4.Mp4Caption
                    .readCaption(bytes)
            }.getOrNull()
        return report(records.size, memory, caption, null)
    }

    private fun report(
        recordCount: Int,
        memory: Memory,
        caption: String?,
        mpfIntact: Boolean?,
    ): VerifyReport {
        val survival =
            when {
                recordCount > 0 -> Survival.FULL
                !caption.isNullOrBlank() -> Survival.CAPTION_ONLY
                else -> Survival.GONE
            }
        return VerifyReport(
            recordCount = recordCount,
            hasNote = memory.currentNote != null,
            audioClips = memory.audio.size,
            captionVisible = !caption.isNullOrBlank(),
            mpfIntact = mpfIntact,
            summary = survival,
        )
    }
}
