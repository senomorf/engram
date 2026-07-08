package photos.engram.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import photos.engram.app.AppContainer
import photos.engram.app.data.db.MediaItemEntity
import photos.engram.app.writeback.WriteOutcome

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

    fun refresh() {
        if (reconciling.value) return
        reconciling.value = true
        viewModelScope.launch {
            runCatching { container.reconciler.reconcile() }
            strippedState.value = runCatching { container.stripRepair.strippedItems() }.getOrDefault(emptyList())
            reconciling.value = false
        }
    }

    fun repairAll() {
        viewModelScope.launch {
            for (item in strippedState.value) {
                val outcome = container.stripRepair.repair(item)
                if (outcome is WriteOutcome.Failed && outcome.reason == "media write rejected") {
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
