package cam.engram.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cam.engram.app.R
import cam.engram.app.export.ArchiveExporter
import cam.engram.app.export.ExportResult
import cam.engram.app.export.SafArchiveSink
import cam.engram.app.verify.BackupVerifier
import cam.engram.format.read.Survival
import kotlinx.coroutines.launch

internal sealed interface ExportState {
    data object Idle : ExportState

    data object Running : ExportState

    data class Done(
        val result: ExportResult,
    ) : ExportState

    data class Failed(
        val message: String,
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

@Composable
fun ToolsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = currentAppContainer()
    val scope = rememberCoroutineScope()
    var export by remember { mutableStateOf<ExportState>(ExportState.Idle) }
    var verify by remember { mutableStateOf<VerifyState>(VerifyState.Idle) }
    // resolved in composition; the failure lambda below runs off the UI thread
    val unknownError = stringResource(R.string.error_unknown)

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                scope.launch {
                    export = ExportState.Running
                    export =
                        runCatching {
                            val sink = SafArchiveSink.open(context, uri) ?: error("cannot open chosen folder")
                            ArchiveExporter(container.db, container.access).exportTo(sink)
                        }.fold(
                            onSuccess = { ExportState.Done(it) },
                            onFailure = { ExportState.Failed(it.message ?: unknownError) },
                        )
                }
            }
        }

    val verifyLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    verify = VerifyState.Running
                    val report = BackupVerifier(container.access).verify(uri.toString())
                    verify = VerifyState.Done(report.summary, report.audioClips, report.corruptCount)
                }
            }
        }

    EngramScaffold(title = stringResource(R.string.open_tools), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.tools_export_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.tools_export_hint), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { exportLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tools_export_button))
            }
            ExportStatus(export)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.tools_verify_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.tools_verify_hint), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { verifyLauncher.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tools_verify_button))
            }
            VerifyStatus(verify)
        }
    }
}

@Composable
internal fun ExportStatus(state: ExportState) {
    when (state) {
        ExportState.Idle -> Unit
        ExportState.Running -> Text(stringResource(R.string.tools_exporting))
        is ExportState.Done ->
            Column {
                Text(stringResource(R.string.tools_export_done, state.result.itemCount, state.result.audioCount))
                if (state.result.failedCount > 0) {
                    Text(stringResource(R.string.tools_export_partial, state.result.failedCount))
                }
            }
        is ExportState.Failed -> Text(stringResource(R.string.tools_export_failed, state.message))
    }
}

@Composable
internal fun VerifyStatus(state: VerifyState) {
    when (state) {
        VerifyState.Idle -> Unit
        VerifyState.Running -> Text(stringResource(R.string.tools_verifying))
        is VerifyState.Done ->
            Text(
                when (state.survival) {
                    Survival.FULL -> stringResource(R.string.tools_survival_full, state.audioClips)
                    Survival.DAMAGED -> stringResource(R.string.tools_survival_damaged, state.corruptCount)
                    Survival.CAPTION_ONLY -> stringResource(R.string.tools_survival_caption)
                    Survival.GONE -> stringResource(R.string.tools_survival_gone)
                    Survival.UNREADABLE -> stringResource(R.string.tools_survival_unreadable)
                },
            )
    }
}
