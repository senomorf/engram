package photos.engram.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import photos.engram.app.AppContainer
import photos.engram.app.data.db.DraftEntity
import photos.engram.app.data.db.MediaItemEntity
import photos.engram.app.writeback.Annotation
import photos.engram.app.writeback.WriteOutcome
import java.io.File

sealed interface SaveUi {
    data object Idle : SaveUi

    data object Saving : SaveUi

    /** Media write was rejected: the screen must obtain MediaStore consent and retry. */
    data object Rejected : SaveUi

    data class Error(
        val reason: String,
    ) : SaveUi

    data object Saved : SaveUi
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

    fun onTextChange(value: String) {
        state.value = state.value.copy(text = value)
        saveJob?.cancel()
        saveJob =
            viewModelScope.launch {
                delay(DEBOUNCE_MILLIS)
                persistDraft()
            }
    }

    fun startRecording() {
        if (state.value.recording) return
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
        state.value.audioPath?.let { File(it).delete() }
        state.value = state.value.copy(audioPath = null)
        viewModelScope.launch { persistDraft() }
    }

    fun hasContent(): Boolean = state.value.text.isNotBlank() || state.value.audioPath != null

    fun save() {
        val s = state.value
        val item = s.item ?: return
        if (!hasContent()) {
            state.value = s.copy(save = SaveUi.Saved)
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
                        state.value.copy(save = SaveUi.Saved)
                    }
                    is WriteOutcome.Failed ->
                        if (outcome.reason == "media write rejected") {
                            state.value.copy(save = SaveUi.Rejected)
                        } else {
                            state.value.copy(save = SaveUi.Error(outcome.reason))
                        }
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
