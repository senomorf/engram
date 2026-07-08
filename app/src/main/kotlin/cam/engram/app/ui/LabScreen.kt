package cam.engram.app.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import cam.engram.app.R
import cam.engram.app.appContainer
import cam.engram.app.writeback.Annotation
import cam.engram.app.writeback.WriteOutcome
import kotlinx.coroutines.launch

/**
 * Developer lab: an on-device transcription check (language-aware via the shared
 * SpeechInput, following the app locale) plus a one-tap write-back spike. Debug
 * surface by design; the transcription feature itself now lives in Annotate.
 */
@Composable
fun LabScreen(onBack: () -> Unit) {
    val transcripts = remember { mutableStateListOf<String>() }
    val speech = rememberSpeechInput { transcripts.add(0, it) }
    EngramScaffold(title = stringResource(R.string.lab_title), onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(if (speech.available) R.string.lab_on_device_yes else R.string.lab_on_device_no),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = speech.start, enabled = speech.available, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.lab_speak))
            }
            labStatusRes(speech.status)?.let { Text(stringResource(it)) }
            LazyColumn(Modifier.weight(1f)) {
                items(transcripts) { t -> Text(text = t, modifier = Modifier.padding(vertical = 4.dp)) }
            }
            SpikeSection()
        }
    }
}

private fun labStatusRes(status: DictationStatus): Int? =
    when (status) {
        DictationStatus.Listening -> R.string.dictation_listening
        DictationStatus.Processing -> R.string.dictation_processing
        DictationStatus.Error -> R.string.dictation_error
        else -> null
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
