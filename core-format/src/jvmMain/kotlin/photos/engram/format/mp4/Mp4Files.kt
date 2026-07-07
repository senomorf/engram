package photos.engram.format.mp4

import photos.engram.format.records.EngramRecord
import photos.engram.format.records.RecordHit
import photos.engram.format.records.RecordStream
import java.io.File
import java.io.RandomAccessFile

/** Streaming variant for real videos; keeps only box headers and our records in memory. */
object Mp4Files {
    fun topLevel(file: File): List<BoxInfo> {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val boxes = mutableListOf<BoxInfo>()
            val header = ByteArray(32)
            var i = 0L
            while (i < len) {
                raf.seek(i)
                val n = raf.read(header)
                if (n <= 0) throw Mp4FormatException("unexpected end of file at $i")
                val info = Mp4Codec.parseHeader(header.copyOf(n), i, len - i)
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
    ) {
        require(newRecords.isNotEmpty()) { "nothing to embed" }
        require(input.canonicalPath != output.canonicalPath) { "output must differ from input" }
        val boxes = topLevel(input)
        boxes.forEachIndexed { idx, b ->
            if (b.sizeFieldWasZero && idx != boxes.lastIndex && !Mp4Codec.isEngramBox(b)) {
                throw Mp4FormatException("size-zero box before end of file not supported")
            }
        }
        val engramBoxes = boxes.filter { Mp4Codec.isEngramBox(it) }
        if (engramBoxes.size > 1) throw Mp4FormatException("multiple engram boxes")
        val existing = engramBoxes.firstOrNull()
        if (existing != null && existing.offset + existing.boxLength != input.length()) {
            throw Mp4FormatException("existing engram box not at end of file, v0 cannot rewrite")
        }
        val zeroLast = boxes.lastOrNull()?.takeIf { it.sizeFieldWasZero && !Mp4Codec.isEngramBox(it) }
        input.copyTo(output, overwrite = true)
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
            raf.seek(raf.length())
            raf.write(Mp4Codec.buildEngramBox(oldRecords + RecordStream.encode(newRecords)))
        }
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
