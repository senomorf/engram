package cam.engram.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cam.engram.app.AppContainer
import cam.engram.app.data.EngramSettings
import cam.engram.app.work.DigestWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun setRemoteDictation(value: Boolean) = update { container.settings.setRemoteDictation(value) }

    fun setDynamicColor(value: Boolean) = update { container.settings.setDynamicColor(value) }

    // returns the Job so a caller that must know the write landed can await it. The UI never
    // does (a toggle is fire-and-forget), but a test can join instead of polling on a wall
    // clock: the settings store writes through DataStore, whose own scope no test dispatcher
    // can drain, so joining the job is the only deterministic way to observe completion.
    private fun update(block: suspend () -> Unit): Job = viewModelScope.launch { block() }
}
