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
import java.io.File

data class AnnotateState(
    val item: MediaItemEntity? = null,
    val text: String = "",
    val audioPath: String? = null,
    val recording: Boolean = false,
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
