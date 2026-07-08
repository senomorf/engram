package cam.engram.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: SettingsViewModel =
        viewModel(
            factory = viewModelFactory { initializer { SettingsViewModel(context.appContainer()) } },
        )
    val s by vm.settings.collectAsState()
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
            }
            ToggleRow(
                label = stringResource(R.string.settings_screenshots),
                checked = s.includeScreenshots,
                onChange = vm::setScreenshots,
            )
            ToggleRow(
                label = stringResource(R.string.settings_digest),
                checked = s.digestEnabled,
                onChange = vm::setDigest,
            )
            Text(stringResource(R.string.settings_digest_hour, s.digestHour))
            Slider(
                value = s.digestHour.toFloat(),
                onValueChange = { vm.setDigestHour(it.toInt()) },
                valueRange = 0f..23f,
                steps = 22,
            )
            ToggleRow(
                label = stringResource(R.string.settings_burst),
                checked = s.burstNudgeEnabled,
                onChange = vm::setBurst,
            )
            ToggleRow(
                label = stringResource(R.string.settings_enrichment_network),
                checked = s.enrichmentNetworkEnabled,
                onChange = vm::setEnrichmentNetwork,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
