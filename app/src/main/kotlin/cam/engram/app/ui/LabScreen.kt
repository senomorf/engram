package cam.engram.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cam.engram.app.R
import cam.engram.app.appContainer
import cam.engram.app.writeback.Annotation
import cam.engram.app.writeback.WriteOutcome
import kotlinx.coroutines.launch

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
            LazyColumn(Modifier.weight(1f)) {
                items(transcripts) { t ->
                    Text(text = t, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            SpikeSection()
        }
    }
}

/** Track A3 debug entry: one-tap write-back against the newest real photo. */
@Composable
private fun SpikeSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = context.appContainer()
    var result by remember { mutableStateOf("") }

    suspend fun run(): String {
        val item =
            container.db
                .media()
                .all()
                .maxByOrNull { it.takenAtMillis } ?: return "no indexed media, open the queue first"
        val started = System.currentTimeMillis()
        val outcome =
            container.writeBack.write(
                item,
                Annotation(noteText = "engram spike $started", audioFile = null),
            )
        val took = System.currentTimeMillis() - started
        return when (outcome) {
            is WriteOutcome.Success -> "ok: ${outcome.recordCount} record(s) in ${took}ms, ${item.relativePath}"
            is WriteOutcome.Failed -> outcome.reason
        }
    }

    val consent =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            if (r.resultCode == android.app.Activity.RESULT_OK) scope.launch { result = run() }
        }
    Text(stringResource(R.string.lab_spike_title), style = MaterialTheme.typography.titleMedium)
    Button(
        onClick = {
            scope.launch {
                val out = run()
                result = out
                if (out == "media write rejected") {
                    container.db
                        .media()
                        .all()
                        .maxByOrNull { it.takenAtMillis }
                        ?.let { item ->
                            container.consentGate
                                .consentNeeded(listOf(item.uri))
                                ?.let { consent.launch(IntentSenderRequest.Builder(it).build()) }
                        }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.lab_spike_run))
    }
    if (result.isNotEmpty()) Text(result)
    Text(stringResource(R.string.lab_spike_hint), style = MaterialTheme.typography.bodySmall)
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
