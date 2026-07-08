package cam.engram.app.ui

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cam.engram.app.R
import cam.engram.format.read.Memory
import coil3.compose.AsyncImage
import java.io.File

@Composable
fun MemoryDetailScreen(
    mediaId: Long,
    onAnnotate: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val container = currentAppContainer()
    // observe the row so returning from annotate (which bumps lastScanMillis)
    // reloads the memory instead of showing the pre-annotation state (review F7)
    val item by remember(mediaId) { container.db.media().flowById(mediaId) }
        .collectAsState(initial = null)
    val uri = item?.uri
    var memory by remember { mutableStateOf<Memory?>(null) }
    LaunchedEffect(item?.mediaId, item?.lastScanMillis, item?.recordCount) {
        memory = item?.let { container.memoryReader.read(it) }
    }
    EngramScaffold(title = stringResource(R.string.detail_title), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            )
            val m = memory
            if (m != null) {
                m.currentNote?.let {
                    Text(it.text, style = MaterialTheme.typography.bodyLarge)
                }
                if (m.audio.isNotEmpty()) {
                    Text(stringResource(R.string.detail_voice), style = MaterialTheme.typography.titleSmall)
                    m.audio.forEach { clip -> AudioRow(clip.data, clip.mime) }
                }
                val place = m.enrichment["place"]
                val weather = m.enrichment["weather"]
                if (place != null || weather != null) {
                    Text(
                        text = listOfNotNull(place, weather).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (m.noteHistory.size > 1) {
                    HorizontalDivider()
                    Text(stringResource(R.string.detail_history), style = MaterialTheme.typography.titleSmall)
                    m.noteHistory.drop(1).forEach {
                        Text("• ${it.text}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Button(onClick = { onAnnotate(mediaId) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.detail_add_more))
            }
        }
    }
}

@Composable
private fun AudioRow(
    data: ByteArray,
    mime: String,
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val file =
        remember(data) {
            val ext = if (mime.contains("mp4") || mime.contains("aac")) "m4a" else "ogg"
            File.createTempFile("engram-play", ".$ext", context.cacheDir).apply { writeBytes(data) }
        }
    DisposableEffect(file) {
        onDispose {
            player?.release()
            player = null
            file.delete()
        }
    }
    AssistChip(
        onClick = {
            player?.let {
                it.release()
                player = null
                return@AssistChip
            }
            player =
                MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        it.release()
                        player = null
                    }
                    prepare()
                    start()
                }
        },
        label = { Text(stringResource(if (player != null) R.string.annotate_stop else R.string.annotate_play)) },
    )
}
