package cam.engram.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cam.engram.app.R
import cam.engram.app.appContainer
import coil3.compose.AsyncImage
import java.io.File

@Composable
fun AnnotateScreen(
    mediaIds: List<Long>,
    startIndex: Int,
    onDone: () -> Unit,
) {
    var index by remember { mutableStateOf(startIndex.coerceIn(0, mediaIds.lastIndex)) }
    val mediaId = mediaIds[index]
    AnnotateCard(
        mediaId = mediaId,
        position = index + 1,
        total = mediaIds.size,
        onNext = { if (index < mediaIds.lastIndex) index++ else onDone() },
    )
}

@Composable
private fun AnnotateCard(
    mediaId: Long,
    position: Int,
    total: Int,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    val vm: AnnotateViewModel =
        viewModel(
            key = "annotate-$mediaId",
            factory =
                viewModelFactory {
                    initializer {
                        AnnotateViewModel(
                            container = context.appContainer(),
                            mediaId = mediaId,
                            draftsDir = File(context.filesDir, "drafts"),
                        )
                    }
                },
        )
    val ui by vm.ui.collectAsState()
    val speech = rememberSpeechInput { spoken -> vm.onTextChange(Dictation.merge(ui.text, spoken)) }
    val consentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) vm.save()
        }
    LaunchedEffect(ui.save) {
        when (val save = ui.save) {
            is SaveUi.Saved -> {
                if (save.overSoftCap) {
                    // a Toast, not a Snackbar, on purpose: this fires as we navigate to the
                    // next photo, so the message must outlive this composition
                    android.widget.Toast
                        .makeText(context, R.string.annotate_over_cap, android.widget.Toast.LENGTH_LONG)
                        .show()
                }
                vm.consumeSaved()
                onNext()
            }
            is SaveUi.Rejected -> {
                ui.item?.let { item ->
                    context
                        .appContainer()
                        .consentGate
                        .consentNeeded(listOf(item.uri))
                        ?.let { consentLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                }
            }
            else -> Unit
        }
    }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.annotate_progress, position, total),
                    style = MaterialTheme.typography.labelLarge,
                )
                TextButton(onClick = onNext) { Text(stringResource(R.string.annotate_skip)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AsyncImage(
                    model = ui.item?.uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            OutlinedTextField(
                value = ui.text,
                onValueChange = vm::onTextChange,
                label = { Text(stringResource(R.string.annotate_note_hint)) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                minLines = 2,
                trailingIcon =
                    if (!speech.available) {
                        null
                    } else {
                        {
                            IconButton(onClick = speech.start) {
                                Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.annotate_dictate))
                            }
                        }
                    },
                supportingText = dictationSupportingText(speech.status),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RecordButton(
                    recording = ui.recording,
                    onStart = vm::startRecording,
                    onStop = vm::stopRecording,
                    modifier = Modifier.weight(1f),
                )
                ui.audioPath?.let { path ->
                    AudioChip(path = path, onDiscard = vm::discardAudio)
                }
            }
            (ui.save as? SaveUi.Error)?.let {
                Text(
                    text = stringResource(R.string.annotate_error, it.reason),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Button(
                onClick = vm::save,
                enabled = ui.save !is SaveUi.Saving,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(
                    stringResource(
                        when {
                            ui.save is SaveUi.Saving -> R.string.annotate_saving
                            vm.hasContent() -> R.string.annotate_save
                            else -> R.string.annotate_done
                        },
                    ),
                )
            }
        }
    }
}

// live dictation state shown under the note field; silent when idle or when
// voice input is unavailable (the absent mic already signals that)
private fun dictationSupportingText(status: DictationStatus): (@Composable () -> Unit)? {
    val res =
        when (status) {
            DictationStatus.Listening -> R.string.dictation_listening
            DictationStatus.Processing -> R.string.dictation_processing
            DictationStatus.Error -> R.string.dictation_error
            DictationStatus.Idle, DictationStatus.Unavailable -> null
        } ?: return null
    return { Text(stringResource(res)) }
}

@Composable
private fun RecordButton(
    recording: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            micGranted = ok
        }
    FilledTonalButton(
        onClick = { if (!micGranted) launcher.launch(Manifest.permission.RECORD_AUDIO) },
        modifier =
            modifier.pointerInput(micGranted) {
                if (micGranted) {
                    detectTapGestures(
                        onPress = {
                            onStart()
                            tryAwaitRelease()
                            onStop()
                        },
                    )
                }
            },
    ) {
        Text(
            text =
                stringResource(
                    if (recording) R.string.annotate_recording else R.string.annotate_hold_to_record,
                ),
        )
    }
}

@Composable
private fun AudioChip(
    path: String,
    onDiscard: () -> Unit,
) {
    var playing by remember { mutableStateOf<MediaPlayer?>(null) }
    // release the player and its file descriptor if the chip leaves composition
    // mid-playback, e.g. navigating away (review F8)
    DisposableEffect(path) {
        onDispose {
            playing?.release()
            playing = null
        }
    }
    AssistChip(
        onClick = {
            playing?.let {
                it.release()
                playing = null
                return@AssistChip
            }
            playing =
                MediaPlayer().apply {
                    setDataSource(path)
                    setOnCompletionListener {
                        it.release()
                        playing = null
                    }
                    prepare()
                    start()
                }
        },
        label = {
            Text(
                stringResource(
                    if (playing != null) R.string.annotate_stop else R.string.annotate_play,
                ),
            )
        },
    )
    TextButton(onClick = {
        playing?.release()
        playing = null
        onDiscard()
    }) {
        Text(stringResource(R.string.annotate_delete_audio))
    }
}
