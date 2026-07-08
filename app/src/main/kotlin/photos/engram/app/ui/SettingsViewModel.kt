package photos.engram.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import photos.engram.app.AppContainer
import photos.engram.app.data.EngramSettings
import photos.engram.app.work.DigestWorker

class SettingsViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val settings: StateFlow<EngramSettings> =
        container.settings.settings
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EngramSettings())

    fun setScreenshots(value: Boolean) = update { container.settings.setIncludeScreenshots(value) }

    fun setDigest(value: Boolean) =
        update {
            container.settings.setDigestEnabled(value)
            DigestWorker.reschedule(container.appContext, settings.value.digestHour, value)
        }

    fun setDigestHour(value: Int) =
        update {
            container.settings.setDigestHour(value)
            DigestWorker.reschedule(container.appContext, value, settings.value.digestEnabled)
        }

    fun setBurst(value: Boolean) = update { container.settings.setBurstNudge(value) }

    fun setEnrichmentNetwork(value: Boolean) = update { container.settings.setEnrichmentNetwork(value) }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
