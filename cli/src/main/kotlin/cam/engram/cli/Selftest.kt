package cam.engram.cli

import cam.engram.format.jpeg.JpegCodec
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.jpeg.MpfInspector
import cam.engram.format.jpeg.Segment
import cam.engram.format.jpeg.isXmpApp1
import cam.engram.format.jpeg.xmpPacket
import cam.engram.format.mp4.Mp4Codec
import cam.engram.format.png.PngCodec
import cam.engram.format.png.PngEmbedder
import cam.engram.format.records.AudioPayload
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.records.RecordStream
import cam.engram.format.testing.SyntheticMedia
import cam.engram.format.xmp.XmpCoreEngine

internal fun selftest() {
    val xmp = XmpCoreEngine()
    val note = "selftest memory"
    val noteRec = EngramRecord(RecordKind.Note, 1, note.encodeToByteArray())
    val audioRec = EngramRecord(RecordKind.Audio, 1, AudioPayload.encode("audio/ogg", ByteArray(64) { it.toByte() }))

    val jpeg = JpegEmbedder(xmp).embed(SyntheticMedia.jpegWithMpfSecondary(), listOf(noteRec, audioRec), note)
    checkSelf("jpeg records", RecordStream.scan(jpeg).count { it.decoded.crcOk } == 2)
    checkSelf("jpeg mpf intact", MpfInspector.inspect(jpeg).valid)
    val packet =
        JpegCodec
            .parse(jpeg)
            .filterIsInstance<Segment>()
            .first { it.isXmpApp1() }
            .xmpPacket()
    checkSelf("jpeg dual-write", xmp.read(packet).description == note)

    val png = PngEmbedder(xmp).embed(SyntheticMedia.png1x1(), listOf(noteRec), note)
    val pngFile = PngCodec.parse(png)
    checkSelf("png engram chunk", pngFile.chunks.any { it.type == PngCodec.ENGRAM_CHUNK })
    checkSelf("png crcs", pngFile.chunks.all { it.crcOk })
    checkSelf("png dual-write", xmp.read(pngFile.chunks.firstNotNullOf { PngCodec.xmpPacket(it) }).description == note)

    val mp4 = Mp4Codec.embed(SyntheticMedia.mp4Minimal(), listOf(noteRec, audioRec))
    checkSelf("mp4 records", Mp4Codec.readRecords(mp4).count { it.decoded.crcOk } == 2)

    println("selftest: all checks passed")
}

private fun checkSelf(
    name: String,
    ok: Boolean,
) {
    if (!ok) error("selftest failed: $name")
    println("  ok: $name")
}
