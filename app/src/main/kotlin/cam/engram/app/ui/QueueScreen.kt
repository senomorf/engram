package cam.engram.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cam.engram.app.DeviceOnly
import cam.engram.app.R
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.media.MediaAccess
import cam.engram.app.data.media.MediaPermissions
import coil3.compose.AsyncImage

// Requested together so Android 14+ shows the picker with an "Allow all" path (finding H5);
// ACCESS_MEDIA_LOCATION rides along but is optional (its denial only strips a photo's location,
// warned at save time, finding 1)
private val requestedPermissions =
    arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )

@Composable
fun QueueScreen(
    onAnnotate: (List<Long>, Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // re-check on resume: an Android 14 partial grant lapses after backgrounding, and the user
    // may grant full access from settings; a one-time read would leave the queue stale (H5)
    var access by remember { mutableStateOf(MediaPermissions.state(context)) }
    LifecycleResumeEffect(Unit) {
        access = MediaPermissions.state(context)
        onPauseOrDispose { }
    }
    when (access) {
        MediaAccess.DENIED -> {
            MediaAccessGate(partial = false, onResult = { access = MediaPermissions.state(context) })
            return
        }
        // whole-library ingestion needs full access; steer a "Select photos" grant to "Allow all"
        MediaAccess.PARTIAL -> {
            MediaAccessGate(partial = true, onResult = { access = MediaPermissions.state(context) })
            return
        }
        MediaAccess.FULL -> Unit
    }
    val container = currentAppContainer()
    val vm: QueueViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer { QueueViewModel(container) }
                },
        )
    LaunchedEffect(Unit) { vm.refresh() }
    val items by vm.queue.collectAsState()
    val busy by vm.busy.collectAsState()
    val stripped by vm.stripped.collectAsState()
    val consentUri by vm.repairNeedsConsent.collectAsState()
    val consentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) vm.consentHandled()
        }
    LaunchedEffect(consentUri) {
        if (consentUri != null) {
            container
                .consentGate
                .consentNeeded(stripped.map { it.uri })
                ?.let { consentLauncher.launch(IntentSenderRequest.Builder(it).build()) }
        }
    }
    val pendingRecovery by vm.pendingRecovery.collectAsState()
    EngramScaffold(title = stringResource(R.string.queue_title, items.size), onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (pendingRecovery.isNotEmpty()) RecoveryCard(pendingRecovery, vm::recoveryConsentHandled)
            if (stripped.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.queue_stripped_banner, stripped.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = vm::repairAll, modifier = Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.queue_restore))
                        }
                    }
                }
            }
            when {
                busy && items.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                items.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.queue_empty))
                    }
                else -> QueueGrid(items, onAnnotate)
            }
        }
    }
}

@Composable
private fun QueueGrid(
    items: List<MediaItemEntity>,
    onAnnotate: (List<Long>, Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items.size, key = { items[it].mediaId }) { index ->
            AsyncImage(
                model = items[index].uri,
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clickable { onAnnotate(items.map { it.mediaId }, index) },
            )
        }
    }
}

// Device-only: MediaStore.createWriteRequest yields a launchable IntentSender only on a real
// device, so the consent launch cannot run under Robolectric (verified via docs/device-qa.md)
@DeviceOnly
@Composable
private fun RecoveryCard(
    uris: List<String>,
    onConsentResult: () -> Unit,
) {
    val container = currentAppContainer()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) onConsentResult()
        }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.queue_recovery_banner, uris.size),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {
                    container
                        .consentGate
                        .consentNeeded(uris)
                        ?.let { launcher.launch(IntentSenderRequest.Builder(it).build()) }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.queue_recovery_restore))
            }
        }
    }
}

// Denied shows the rationale; partial (Android 14 "Select photos") steers toward "Allow all",
// since whole-library ingestion cannot run on an ephemeral subset (finding H5). The launcher
// result is re-read as access state (which may become full, still partial, or denied).
@Composable
private fun MediaAccessGate(
    partial: Boolean,
    onResult: () -> Unit,
) {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text =
                    stringResource(
                        if (partial) R.string.queue_partial_media_rationale else R.string.queue_permission_rationale,
                    ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                onClick = { launcher.launch(requestedPermissions) },
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(stringResource(if (partial) R.string.queue_allow_all else R.string.grant_access))
            }
        }
    }
}
