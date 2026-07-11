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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cam.engram.app.DeviceOnly
import cam.engram.app.R
import cam.engram.app.data.db.MediaItemEntity
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
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
    val container = currentAppContainer()
    val vm: AnnotateViewModel =
        viewModel(
            key = "annotate-$mediaId",
            factory =
                viewModelFactory {
                    initializer {
                        AnnotateViewModel(
                            container = container,
                            mediaId = mediaId,
                            draftsDir = File(context.filesDir, "drafts"),
                        )
                    }
                },
        )
    val ui by vm.ui.collectAsState()
    val store = remember { container.settings }
    val scope = rememberCoroutineScope()
    // recording language is decoupled from the UI language: an explicit choice,
    // else the current UI language (review item 4)
    val prefs = store.settings.collectAsState(initial = null).value
    val uiLanguageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val dictationTag = Dictation.resolveDictationTag(prefs?.dictationLanguage, uiLanguageTag)
    val speech =
        rememberDictation(
            dictationTag = dictationTag,
            remoteConsent = prefs?.remoteDictationEnabled == true,
            onEnableRemote = { scope.launch { store.setRemoteDictation(true) } },
        ) { spoken -> vm.onTextChange(Dictation.merge(ui.text, spoken)) }
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
                    container
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
            // the ViewModel guards are authoritative; disabling the inputs makes the
            // freeze visible instead of silently swallowing taps (finding D)
            val inputsEnabled = ui.save !is SaveUi.Saving
            NoteField(
                value = ui.text,
                onValueChange = vm::onTextChange,
                speech = speech,
                dictationTag = dictationTag,
                onPickLanguage = { tag -> scope.launch { store.setDictationLanguage(tag) } },
                enabled = inputsEnabled,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
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
                    enabled = inputsEnabled,
                    modifier = Modifier.weight(1f),
                )
                ui.audioPath?.let { path ->
                    AudioChip(path = path, onDiscard = vm::discardAudio, discardEnabled = inputsEnabled)
                }
            }
            (ui.save as? SaveUi.Error)?.let {
                Text(
                    text = stringResource(R.string.annotate_error, it.reason),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            SaveWithLocationGuard(
                saveState = ui.save,
                recording = ui.recording,
                labelRes =
                    when {
                        ui.save is SaveUi.Saving -> R.string.annotate_saving
                        vm.hasContent() -> R.string.annotate_save
                        else -> R.string.annotate_done
                    },
                stripsLocation = stripsLocation(context, ui.item),
                onSave = vm::save,
            )
        }
    }
}

@Composable
private fun SaveWithLocationGuard(
    saveState: SaveUi,
    recording: Boolean,
    labelRes: Int,
    stripsLocation: Boolean,
    onSave: () -> Unit,
) {
    var warned by remember { mutableStateOf(false) }
    var showWarning by remember { mutableStateOf(false) }
    Button(
        onClick = { if (stripsLocation && !warned) showWarning = true else onSave() },
        enabled = saveState !is SaveUi.Saving && !recording,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) { Text(stringResource(labelRes)) }
    if (showWarning) {
        ConfirmDialog(
            titleRes = R.string.annotate_location_warning_title,
            bodyRes = R.string.annotate_location_warning_body,
            confirmRes = R.string.annotate_location_warning_confirm,
            onConfirm = {
                warned = true
                showWarning = false
                onSave()
            },
            onDismiss = { showWarning = false },
        )
    }
}

// annotating strips GPS when the item is an image and location access was denied
private fun stripsLocation(
    context: android.content.Context,
    item: MediaItemEntity?,
): Boolean =
    item?.isVideo == false &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) !=
        PackageManager.PERMISSION_GRANTED

@Composable
private fun NoteField(
    value: String,
    onValueChange: (String) -> Unit,
    speech: SpeechInput,
    dictationTag: String,
    onPickLanguage: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(stringResource(R.string.annotate_note_hint)) },
        modifier = modifier,
        minLines = 2,
        trailingIcon =
            if (!speech.available) {
                null
            } else {
                { DictationControls(dictationTag, onPickLanguage, speech.start) }
            },
        supportingText = dictationSupportingText(speech.status),
    )
}

// recording-language badge (decoupled from UI language) plus the dictate mic
@Composable
internal fun DictationControls(
    dictationTag: String,
    onPickLanguage: (String) -> Unit,
    onDictate: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { menuOpen = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(Dictation.shortLabel(dictationTag), style = MaterialTheme.typography.labelLarge)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Dictation.supportedLanguages.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang.label) },
                        onClick = {
                            onPickLanguage(lang.tag)
                            menuOpen = false
                        },
                    )
                }
            }
        }
        IconButton(onClick = onDictate) {
            Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.annotate_dictate))
        }
    }
}

// live dictation state shown under the note field; silent when idle
private fun dictationSupportingText(status: DictationStatus): (@Composable () -> Unit)? {
    val res =
        when (status) {
            DictationStatus.Listening -> R.string.dictation_listening
            DictationStatus.Processing -> R.string.dictation_processing
            DictationStatus.Error -> R.string.dictation_error
            DictationStatus.LanguageUnavailable -> R.string.dictation_language_unavailable
            DictationStatus.Idle -> null
        } ?: return null
    return { Text(stringResource(res)) }
}

@Composable
private fun RecordButton(
    recording: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    enabled: Boolean,
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
        enabled = enabled,
        modifier =
            modifier.pointerInput(micGranted, enabled) {
                if (micGranted && enabled) {
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

@DeviceOnly
@Composable
private fun AudioChip(
    path: String,
    onDiscard: () -> Unit,
    discardEnabled: Boolean,
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
    TextButton(
        enabled = discardEnabled,
        onClick = {
            playing?.release()
            playing = null
            onDiscard()
        },
    ) {
        Text(stringResource(R.string.annotate_delete_audio))
    }
}
