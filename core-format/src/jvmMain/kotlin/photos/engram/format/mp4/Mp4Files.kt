package photos.engram.format.mp4

import photos.engram.format.records.EngramRecord
import photos.engram.format.records.RecordHit
import photos.engram.format.records.RecordStream
import java.io.File
import java.io.RandomAccessFile

enum class CaptionOutcome { WRITTEN, SKIPPED_UNSAFE_LAYOUT, NOT_REQUESTED }

/** Streaming variant for real videos; keeps only box headers, moov and our records in memory. */
object Mp4Files {
    fun topLevel(file: File): List<BoxInfo> {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val boxes = mutableListOf<BoxInfo>()
            var i = 0L
            while (i < len) {
                val want = minOf(32L, len - i).toInt()
                val header = ByteArray(want)
                raf.seek(i)
                raf.readFully(header)
                val info = Mp4Codec.parseHeader(header, i, len - i)
                if (i + info.boxLength > len) throw Mp4FormatException("box overruns file at $i")
                boxes += info
                i += info.boxLength
            }
            return boxes
        }
    }

    fun appendRecords(
        input: File,
        output: File,
        newRecords: List<EngramRecord>,
        caption: String? = null,
    ): CaptionOutcome {
        require(newRecords.isNotEmpty()) { "nothing to embed" }
        require(input.canonicalPath != output.canonicalPath) { "output must differ from input" }
        val boxes = topLevel(input)
        boxes.forEachIndexed { idx, b ->
            if (b.sizeFieldWasZero && idx != boxes.lastIndex) {
                throw Mp4FormatException("size-zero box before end of file not supported")
            }
            if (Mp4Codec.isEngramBox(b) && idx != boxes.lastIndex) {
                throw Mp4FormatException("existing engram box not at end of file, refusing to rewrite")
            }
        }
        val existing = boxes.lastOrNull()?.takeIf { Mp4Codec.isEngramBox(it) }
        val zeroLast = boxes.lastOrNull()?.takeIf { it.sizeFieldWasZero && !Mp4Codec.isEngramBox(it) }
        input.copyTo(output, overwrite = true)
        var captionOutcome = CaptionOutcome.NOT_REQUESTED
        RandomAccessFile(output, "rw").use { raf ->
            var oldRecords = ByteArray(0)
            if (existing != null) {
                raf.seek(existing.offset + existing.headerLength)
                oldRecords = ByteArray((existing.boxLength - existing.headerLength).toInt())
                raf.readFully(oldRecords)
                raf.setLength(existing.offset)
            }
            if (zeroLast != null) {
                if (zeroLast.boxLength > 0xFFFFFFFFL) {
                    throw Mp4FormatException("size-zero box too large to materialize")
                }
                raf.seek(zeroLast.offset)
                raf.writeInt(zeroLast.boxLength.toInt())
            }
            if (caption != null) {
                captionOutcome = writeCaption(raf, caption)
            }
            raf.seek(raf.length())
            raf.write(Mp4Codec.buildEngramBox(oldRecords + RecordStream.encode(newRecords)))
        }
        return captionOutcome
    }

    // moov must be the trailing content box; it is rewritten in place at the tail
    private fun writeCaption(
        raf: RandomAccessFile,
        caption: String,
    ): CaptionOutcome {
        val boxes = fileBoxes(raf)
        val moovIdx = boxes.indexOfLast { it.type == "moov" }
        if (moovIdx < 0) return CaptionOutcome.SKIPPED_UNSAFE_LAYOUT
        if (moovIdx != boxes.lastIndex) return CaptionOutcome.SKIPPED_UNSAFE_LAYOUT
        val moov = boxes[moovIdx]
        raf.seek(moov.offset)
        val moovBytes = ByteArray(moov.boxLength.toInt())
        raf.readFully(moovBytes)
        val rebuilt = Mp4Caption.rewriteMoovBox(moovBytes, caption) ?: return CaptionOutcome.SKIPPED_UNSAFE_LAYOUT
        raf.setLength(moov.offset)
        raf.seek(moov.offset)
        raf.write(rebuilt)
        return CaptionOutcome.WRITTEN
    }

    private fun fileBoxes(raf: RandomAccessFile): List<BoxInfo> {
        val len = raf.length()
        val boxes = mutableListOf<BoxInfo>()
        var i = 0L
        while (i < len) {
            val want = minOf(32L, len - i).toInt()
            val header = ByteArray(want)
            raf.seek(i)
            raf.readFully(header)
            val info = Mp4Codec.parseHeader(header, i, len - i)
            if (i + info.boxLength > len) throw Mp4FormatException("box overruns file at $i")
            boxes += info
            i += info.boxLength
        }
        return boxes
    }

    fun readRecords(file: File): List<RecordHit> {
        val engram = topLevel(file).lastOrNull { Mp4Codec.isEngramBox(it) } ?: return emptyList()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(engram.offset + engram.headerLength)
            val payload = ByteArray((engram.boxLength - engram.headerLength).toInt())
            raf.readFully(payload)
            return RecordStream.decodeSequence(payload)
        }
    }
}
