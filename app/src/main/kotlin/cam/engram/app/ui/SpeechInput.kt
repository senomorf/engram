package cam.engram.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

enum class DictationStatus { Idle, Listening, Processing, Error, Unavailable }

/** What a screen needs from voice input: is it usable, its live state, a trigger. */
class SpeechInput(
    val available: Boolean,
    val status: DictationStatus,
    val start: () -> Unit,
)

/**
 * Reusable on-device speech-to-text. The recognizer language follows the app
 * locale (so Russian UI dictates Russian), prefers the offline model, and the
 * recognizer is torn down with the composition. onResult fires with the final
 * transcript; callers decide what to do with it.
 */
@Composable
fun rememberSpeechInput(onResult: (String) -> Unit): SpeechInput {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val languageTag =
        remember(configuration) {
            Dictation.recognizerLanguageTag(configuration.locales[0].toLanguageTag())
        }
    val available = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val latestOnResult = rememberUpdatedState(onResult)
    var status by remember {
        mutableStateOf(if (available) DictationStatus.Idle else DictationStatus.Unavailable)
    }

    val recognizer =
        remember {
            when {
                !available -> null
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else -> SpeechRecognizer.createSpeechRecognizer(context)
            }
        }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    status = DictationStatus.Listening
                }

                override fun onEndOfSpeech() {
                    status = DictationStatus.Processing
                }

                override fun onError(error: Int) {
                    status = DictationStatus.Error
                }

                override fun onResults(results: Bundle?) {
                    val text =
                        results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                    if (!text.isNullOrBlank()) latestOnResult.value(text)
                    status = DictationStatus.Idle
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) {}
            },
        )
        onDispose { recognizer?.destroy() }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && recognizer != null) recognizer.startListening(dictationIntent(languageTag))
        }

    val start: () -> Unit = {
        if (recognizer != null) {
            val granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            if (granted) {
                recognizer.startListening(
                    dictationIntent(languageTag),
                )
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    // fresh holder each recomposition so callers observe the current status
    return SpeechInput(available = available, status = status, start = start)
}

private fun dictationIntent(languageTag: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }
