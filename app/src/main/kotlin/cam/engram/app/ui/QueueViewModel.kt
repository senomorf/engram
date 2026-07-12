package cam.engram.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cam.engram.app.AppContainer
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.writeback.WriteOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QueueViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val queue: StateFlow<List<MediaItemEntity>> =
        container.db
            .media()
            .queue()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val reconciling = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = reconciling

    private val strippedState = MutableStateFlow<List<MediaItemEntity>>(emptyList())
    val stripped: StateFlow<List<MediaItemEntity>> = strippedState

    private val repairState = MutableStateFlow<String?>(null)
    val repairNeedsConsent: StateFlow<String?> = repairState

    // target uris of writes a lost grant left mid-restore after process death (finding C2):
    // the pristine backup is safe, but the live file is still damaged until the user re-grants
    private val pendingRecoveryState = MutableStateFlow<List<String>>(emptyList())
    val pendingRecovery: StateFlow<List<String>> = pendingRecoveryState

    fun refresh() {
        if (reconciling.value) return
        reconciling.value = true
        viewModelScope.launch {
            runCatching { container.reconciler.reconcile() }
            strippedState.value = runCatching { container.stripRepair.strippedItems() }.getOrDefault(emptyList())
            pendingRecoveryState.value = runCatching { container.writeBack.recoverPending() }.getOrDefault(emptyList())
            reconciling.value = false
        }
    }

    // after the user grants the write consent, retrying recovery restores the originals and
    // clears the ones that settled; any still needing consent stay surfaced
    fun recoveryConsentHandled() {
        viewModelScope.launch {
            pendingRecoveryState.value = runCatching { container.writeBack.recoverPending() }.getOrDefault(emptyList())
        }
    }

    fun repairAll() {
        viewModelScope.launch {
            for (item in strippedState.value) {
                val outcome = container.stripRepair.repair(item)
                if (outcome is WriteOutcome.NotOpened) {
                    // screen obtains batch consent for the remaining uris, then retries
                    repairState.value = item.uri
                    return@launch
                }
            }
            strippedState.value = runCatching { container.stripRepair.strippedItems() }.getOrDefault(emptyList())
        }
    }

    fun consentHandled() {
        repairState.value = null
        repairAll()
    }
}
