package photos.engram.app.audio

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/** Seam over MediaRecorder so annotate logic is testable off-device. */
interface VoiceRecorder {
    fun start(output: File)

    /** Returns true when a usable recording was produced. */
    fun stop(): Boolean
}

interface VoiceRecorderFactory {
    fun create(): VoiceRecorder
}

class MediaVoiceRecorder(
    private val context: Context,
) : VoiceRecorder {
    private var recorder: MediaRecorder? = null

    override fun start(output: File) {
        output.parentFile?.mkdirs()
        recorder =
            MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioEncodingBitRate(BIT_RATE)
                setAudioSamplingRate(SAMPLE_RATE)
                setOutputFile(output.absolutePath)
                prepare()
                start()
            }
    }

    override fun stop(): Boolean =
        runCatching {
            recorder?.apply {
                stop()
                release()
            }
        }.also { recorder = null }.isSuccess

    private companion object {
        // mono speech, design A7: Opus at roughly 24 to 32 kbps
        const val BIT_RATE = 32_000
        const val SAMPLE_RATE = 48_000
    }
}
