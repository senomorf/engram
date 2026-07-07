package photos.engram.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import photos.engram.app.R

/**
 * Transcription spike (plan track A4): measures on-device ru-RU quality on the
 * owner's real speech. Debug tool by design; results decide design gate D15.
 */
@Composable
fun LabScreen() {
    val context = LocalContext.current
    val onDevice = remember { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
    val transcripts = remember { mutableStateListOf<String>() }
    val status = remember { mutableStateOf("") }
    val recognizer =
        remember {
            if (onDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }
    DisposableEffect(Unit) {
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text =
                        results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                    if (text != null) transcripts.add(0, text)
                    status.value = ""
                }

                override fun onError(error: Int) {
                    status.value = "error $error"
                }

                override fun onReadyForSpeech(params: Bundle?) {
                    status.value = "listening"
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    status.value = "processing"
                }

                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) {}
            },
        )
        onDispose { recognizer.destroy() }
    }
    val micLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening(recognizer)
        }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.lab_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text =
                    stringResource(
                        if (onDevice) R.string.lab_on_device_yes else R.string.lab_on_device_no,
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {
                    val granted =
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                    if (granted) startListening(recognizer) else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.lab_speak))
            }
            if (status.value.isNotEmpty()) Text(status.value)
            LazyColumn {
                items(transcripts) { t ->
                    Text(text = t, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

private fun startListening(recognizer: SpeechRecognizer) {
    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    recognizer.startListening(intent)
}
