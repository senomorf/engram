package cam.engram.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

enum class DictationStatus { Idle, Listening, Processing, Error, LanguageUnavailable }

/** What a screen needs from voice input: is it usable, its live state, a trigger. */
class SpeechInput(
    val available: Boolean,
    val status: DictationStatus,
    val start: () -> Unit,
)

enum class RecognizerChoice { OnDevice, Remote, None }

// pure consent gate so it is unit-testable off-device: dictation never uses the remote
// recognizer (which may stream audio to a server) unless the user has consented (finding 6)
internal fun chooseRecognizer(
    recognitionAvailable: Boolean,
    onDeviceAvailable: Boolean,
    remoteConsent: Boolean,
): RecognizerChoice =
    when {
        !recognitionAvailable -> RecognizerChoice.None
        onDeviceAvailable -> RecognizerChoice.OnDevice
        remoteConsent -> RecognizerChoice.Remote
        else -> RecognizerChoice.None
    }

/**
 * Reusable on-device speech-to-text. The recognizer language is passed in
 * explicitly (decoupled from the UI language), prefers the offline model, and
 * the recognizer is torn down with the composition. When the chosen language is
 * not installed on-device it best-effort triggers a one-time model download.
 * onResult fires with the final transcript; callers decide what to do with it.
 */
@Composable
fun rememberSpeechInput(
    languageTag: String,
    remoteConsent: Boolean,
    onRemoteConsentNeeded: () -> Unit,
    onResult: (String) -> Unit,
): SpeechInput {
    val context = LocalContext.current
    val available = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val onDeviceAvailable = remember { available && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
    val choice = chooseRecognizer(available, onDeviceAvailable, remoteConsent)
    val latestOnResult = rememberUpdatedState(onResult)
    val latestLang = rememberUpdatedState(languageTag)
    var status by remember {
        mutableStateOf(if (available) DictationStatus.Idle else DictationStatus.Error)
    }

    val recognizer =
        remember(choice) {
            when (choice) {
                RecognizerChoice.OnDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                RecognizerChoice.Remote -> SpeechRecognizer.createSpeechRecognizer(context)
                RecognizerChoice.None -> null
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
                    val languageMissing =
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                    status = if (languageMissing) DictationStatus.LanguageUnavailable else DictationStatus.Error
                    if (languageMissing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // fetch the on-device model so the next attempt works offline
                        runCatching { recognizer.triggerModelDownload(dictationIntent(latestLang.value)) }
                    }
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
            if (granted && recognizer != null) recognizer.startListening(dictationIntent(latestLang.value))
        }

    val start: () -> Unit = {
        if (recognizer != null) {
            val granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            if (granted) {
                recognizer.startListening(dictationIntent(latestLang.value))
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else if (available && !remoteConsent) {
            // only a remote recognizer exists and the user has not consented yet: ask first
            onRemoteConsentNeeded()
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
