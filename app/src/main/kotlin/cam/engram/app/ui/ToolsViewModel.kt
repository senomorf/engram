package cam.engram.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cam.engram.app.AppContainer
import cam.engram.app.export.ArchiveSink
import cam.engram.app.export.ExportResult
import cam.engram.app.verify.BackupVerifier
import cam.engram.format.read.Survival
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal sealed interface ExportState {
    data object Idle : ExportState

    data object Running : ExportState

    data class Done(
        val result: ExportResult,
    ) : ExportState

    data class Failed(
        // null when there is no useful message; the screen shows the generic error
        val message: String?,
    ) : ExportState
}

internal sealed interface VerifyState {
    data object Idle : VerifyState

    data object Running : VerifyState

    data class Done(
        val survival: Survival,
        val audioClips: Int,
        val corruptCount: Int,
    ) : VerifyState
}

/**
 * Owns the tools work and its results. The hand-rolled navigation has no
 * per-screen owner, so this view model is activity scoped: an export keeps
 * running when the user navigates away (no more half-written archive from a
 * cancelled composition scope, finding R8), and the sticky Done/Failed state is
 * the last run's result when they return. The heavy per-file IO runs on the
 * container's io dispatcher inside ArchiveExporter and BackupVerifier.
 */
class ToolsViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val exportFlow = MutableStateFlow<ExportState>(ExportState.Idle)
    internal val exportState: StateFlow<ExportState> = exportFlow
    private val verifyFlow = MutableStateFlow<VerifyState>(VerifyState.Idle)
    internal val verifyState: StateFlow<VerifyState> = verifyFlow

    internal fun export(sink: ArchiveSink?) {
        if (exportFlow.value is ExportState.Running) return
        if (sink == null) {
            exportFlow.value = ExportState.Failed(null)
            return
        }
        viewModelScope.launch {
            exportFlow.value = ExportState.Running
            exportFlow.value =
                runCatching { container.archiveExporter.exportTo(sink) }
                    .fold(
                        onSuccess = { ExportState.Done(it) },
                        onFailure = { ExportState.Failed(it.message) },
                    )
        }
    }

    internal fun verify(uri: String) {
        if (verifyFlow.value is VerifyState.Running) return
        viewModelScope.launch {
            verifyFlow.value = VerifyState.Running
            val report = BackupVerifier(container.access).verify(uri)
            verifyFlow.value = VerifyState.Done(report.summary, report.audioClips, report.corruptCount)
        }
    }
}
