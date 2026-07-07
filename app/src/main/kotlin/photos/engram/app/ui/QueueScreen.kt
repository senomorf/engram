package photos.engram.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import photos.engram.app.R
import photos.engram.app.appContainer
import photos.engram.app.data.db.MediaItemEntity

private val mediaPermissions =
    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

@Composable
fun QueueScreen() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasMediaPermissions(context)) }
    if (!granted) {
        PermissionGate(onGranted = { granted = true })
        return
    }
    val vm: QueueViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer { QueueViewModel(context.appContainer()) }
                },
        )
    LaunchedEffect(Unit) { vm.refresh() }
    val items by vm.queue.collectAsState()
    val busy by vm.busy.collectAsState()
    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                text = stringResource(R.string.queue_title, items.size),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
            when {
                busy && items.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                items.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.queue_empty))
                    }
                else -> QueueGrid(items)
            }
        }
    }
}

@Composable
private fun QueueGrid(items: List<MediaItemEntity>) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.mediaId }) { item ->
            AsyncImage(
                model = item.uri,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        }
    }
}

@Composable
private fun PermissionGate(onGranted: () -> Unit) {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) onGranted()
        }
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.queue_permission_rationale),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                onClick = { launcher.launch(mediaPermissions) },
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(stringResource(R.string.grant_access))
            }
        }
    }
}

private fun hasMediaPermissions(context: android.content.Context): Boolean =
    mediaPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
