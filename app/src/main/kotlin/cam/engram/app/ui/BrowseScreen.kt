package cam.engram.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cam.engram.app.R
import cam.engram.app.appContainer
import cam.engram.app.data.db.MediaItemEntity
import coil3.compose.AsyncImage

@Composable
fun BrowseScreen(
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: BrowseViewModel =
        viewModel(factory = viewModelFactory { initializer { BrowseViewModel(context.appContainer()) } })
    val timeline by vm.timeline.collectAsState()
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val shown = results ?: timeline
    EngramScaffold(title = stringResource(R.string.open_browse), onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::onQueryChange,
                label = { Text(stringResource(R.string.browse_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            if (results != null) R.string.browse_no_results else R.string.browse_empty,
                        ),
                    )
                }
            } else {
                MemoryGrid(shown, onOpen)
            }
        }
    }
}

@Composable
private fun MemoryGrid(
    items: List<MediaItemEntity>,
    onOpen: (Long) -> Unit,
) {
    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.mediaId }) { item ->
            AsyncImage(
                model = item.uri,
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(1.dp)
                        .clickable { onOpen(item.mediaId) },
            )
        }
    }
}
