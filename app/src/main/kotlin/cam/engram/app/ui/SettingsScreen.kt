package cam.engram.app.ui

import android.Manifest
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.LocaleList
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cam.engram.app.R
import android.provider.Settings as SystemSettings

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = currentAppContainer()
    val vm: SettingsViewModel =
        viewModel(
            factory = viewModelFactory { initializer { SettingsViewModel(container) } },
        )
    val s by vm.settings.collectAsState()
    // permission and channel state both feed the same gate Notifier applies at post
    // time; refreshed on resume so returning from system settings updates the hint (D30)
    var notificationsOn by remember { mutableStateOf(notificationsEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsOn = notificationsEnabled(context)
        onPauseOrDispose { }
    }
    val notifLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsOn = notificationsEnabled(context)
        }

    fun requestNotificationsIfMissing() {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    EngramScaffold(title = stringResource(R.string.settings_title), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToggleRow(
                label = stringResource(R.string.settings_screenshots),
                checked = s.includeScreenshots,
                onChange = vm::setScreenshots,
            )
            ToggleRow(
                label = stringResource(R.string.settings_digest),
                checked = s.digestEnabled,
                onChange = { on ->
                    vm.setDigest(on)
                    if (on) requestNotificationsIfMissing()
                },
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
                onChange = { on ->
                    vm.setBurst(on)
                    if (on) requestNotificationsIfMissing()
                },
            )
            if ((s.digestEnabled || s.burstNudgeEnabled) && !notificationsOn) {
                NotificationsDisabledHint(context)
            }
            ToggleRow(
                label = stringResource(R.string.settings_enrichment_network),
                checked = s.enrichmentNetworkEnabled,
                onChange = vm::setEnrichmentNetwork,
            )
            ToggleRow(
                label = stringResource(R.string.settings_remote_dictation),
                checked = s.remoteDictationEnabled,
                onChange = vm::setRemoteDictation,
            )
            ToggleRow(
                label = stringResource(R.string.settings_dynamic_color),
                checked = s.dynamicColor,
                onChange = vm::setDynamicColor,
            )
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleSmall)
            LanguageRow(context)
        }
    }
}

private fun notificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

// nudges are on but the system will drop every notification: say so, and take the
// user straight to the system toggle
@Composable
private fun NotificationsDisabledHint(context: Context) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_notifications_disabled_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        TextButton(onClick = {
            context.startActivity(
                Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }) {
            Text(stringResource(R.string.settings_notifications_open))
        }
    }
}

/**
 * Per-app language override via the framework LocaleManager (API 33+). Setting
 * applicationLocales triggers an activity recreate, so the UI reloads in the
 * chosen locale. Empty list means "follow the system".
 */
@Composable
private fun LanguageRow(context: Context) {
    val localeManager = remember { context.getSystemService(LocaleManager::class.java) }
    var tag by remember {
        mutableStateOf(localeManager.applicationLocales.let { if (it.isEmpty) null else it[0].language })
    }

    fun choose(next: String?) {
        tag = next
        localeManager.applicationLocales =
            if (next == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(next)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = tag == null,
            onClick = { choose(null) },
            label = { Text(stringResource(R.string.settings_language_system)) },
        )
        FilterChip(
            selected = tag == "en",
            onClick = { choose("en") },
            label = { Text(stringResource(R.string.settings_language_english)) },
        )
        FilterChip(
            selected = tag == "ru",
            onClick = { choose("ru") },
            label = { Text(stringResource(R.string.settings_language_russian)) },
        )
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
        // weight keeps a long label from sliding under the switch (wraps instead)
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
