package cam.engram.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cam.engram.app.AppContainer
import cam.engram.app.data.db.DraftEntity
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.writeback.Annotation
import cam.engram.app.writeback.WriteOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface SaveUi {
    data object Idle : SaveUi

    data object Saving : SaveUi

    /** Media write was rejected: the screen must obtain MediaStore consent and retry. */
    data object Rejected : SaveUi

    data class Error(
        val reason: String,
    ) : SaveUi

    data class Saved(
        val overSoftCap: Boolean,
    ) : SaveUi
}

data class AnnotateState(
    val item: MediaItemEntity? = null,
    val text: String = "",
    val audioPath: String? = null,
    val recording: Boolean = false,
    val save: SaveUi = SaveUi.Idle,
)

class AnnotateViewModel(
    private val container: AppContainer,
    private val mediaId: Long,
    private val draftsDir: File,
) : ViewModel() {
    private val state = MutableStateFlow(AnnotateState())
    val ui: StateFlow<AnnotateState> = state

    private val recorder = container.recorderFactory.create()
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val item = container.db.media().byId(mediaId)
            val draft = container.db.drafts().byId(mediaId)
            state.value =
                AnnotateState(
                    item = item,
                    text = draft?.text.orEmpty(),
                    audioPath = draft?.audioPath?.takeIf { File(it).exists() },
                )
        }
    }

    // while a save is in flight its snapshot must stay the truth: edits, new recordings,
    // and audio discards are ignored until it resolves, so the draft delete on success
    // can never discard newer content the write did not carry (finding D)
    private fun frozen() = state.value.save is SaveUi.Saving

    fun onTextChange(value: String) {
        if (frozen()) return
        state.value = state.value.copy(text = value)
        saveJob?.cancel()
        saveJob =
            viewModelScope.launch {
                delay(DEBOUNCE_MILLIS)
                persistDraft()
            }
    }

    fun startRecording() {
        if (frozen() || state.value.recording) return
        val output = File(draftsDir, "$mediaId.ogg")
        runCatching { recorder.start(output) }
            .onSuccess { state.value = state.value.copy(recording = true) }
    }

    fun stopRecording() {
        if (!state.value.recording) return
        val ok = recorder.stop()
        val path = File(draftsDir, "$mediaId.ogg").takeIf { ok && it.exists() && it.length() > 0 }?.absolutePath
        state.value = state.value.copy(recording = false, audioPath = path)
        viewModelScope.launch { persistDraft() }
    }

    fun discardAudio() {
        // the in-flight write reads the audio file; deleting it mid-save would corrupt the save
        if (frozen()) return
        state.value.audioPath?.let { File(it).delete() }
        state.value = state.value.copy(audioPath = null)
        viewModelScope.launch { persistDraft() }
    }

    fun hasContent(): Boolean = state.value.text.isNotBlank() || state.value.audioPath != null

    fun save() {
        val s = state.value
        val item = s.item ?: return
        // no re-entry, and no snapshot of a recording still being written to disk
        if (s.save is SaveUi.Saving || s.recording) return
        if (!hasContent()) {
            state.value = s.copy(save = SaveUi.Saved(overSoftCap = false))
            return
        }
        state.value = s.copy(save = SaveUi.Saving)
        viewModelScope.launch {
            saveJob?.cancel()
            persistDraft()
            val outcome =
                container.writeBack.write(
                    item,
                    Annotation(
                        noteText = s.text.takeIf { it.isNotBlank() },
                        audioFile = s.audioPath?.let { File(it) },
                    ),
                )
            state.value =
                when (outcome) {
                    is WriteOutcome.Success -> {
                        container.db.drafts().delete(mediaId)
                        s.audioPath?.let { File(it).delete() }
                        state.value.copy(save = SaveUi.Saved(overSoftCap = outcome.overSoftCap))
                    }
                    WriteOutcome.NotOpened -> state.value.copy(save = SaveUi.Rejected)
                    is WriteOutcome.Failed -> state.value.copy(save = SaveUi.Error(outcome.reason))
                }
        }
    }

    fun consumeSaved() {
        state.value = state.value.copy(save = SaveUi.Idle)
    }

    private suspend fun persistDraft() {
        val s = state.value
        if (s.text.isBlank() && s.audioPath == null) {
            container.db.drafts().delete(mediaId)
        } else {
            container.db.drafts().upsert(
                DraftEntity(
                    mediaId = mediaId,
                    text = s.text.takeIf { it.isNotBlank() },
                    audioPath = s.audioPath,
                    updatedMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun onCleared() {
        if (state.value.recording) recorder.stop()
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 400L
    }
}
