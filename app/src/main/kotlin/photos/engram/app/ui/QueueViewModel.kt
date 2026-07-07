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

    fun refresh() {
        if (reconciling.value) return
        reconciling.value = true
        viewModelScope.launch {
            runCatching { container.reconciler.reconcile() }
            reconciling.value = false
        }
    }
}
