package cam.engram.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cam.engram.app.R

@Composable
fun HomeScreen(
    onOpenQueue: () -> Unit,
    onOpenBrowse: () -> Unit = {},
    onOpenTools: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenLab: () -> Unit = {},
) {
    val container = currentAppContainer()
    val vm: HomeViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer { HomeViewModel(container) }
                },
        )
    val counts by vm.counts.collectAsState()
    Scaffold { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Card(modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CountCell(counts.total, stringResource(R.string.count_total))
                    CountCell(counts.annotated, stringResource(R.string.count_annotated))
                    CountCell(counts.waiting, stringResource(R.string.count_waiting))
                }
            }
            Button(
                onClick = onOpenQueue,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text(stringResource(R.string.open_queue))
            }
            TextButton(
                onClick = onOpenBrowse,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.open_browse))
            }
            TextButton(onClick = onOpenTools) {
                Text(stringResource(R.string.open_tools))
            }
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.open_settings))
            }
            TextButton(onClick = onOpenLab) {
                Text(stringResource(R.string.open_lab))
            }
        }
    }
}

@Composable
private fun CountCell(
    value: Int,
    label: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "$value", style = MaterialTheme.typography.headlineMedium)
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}
