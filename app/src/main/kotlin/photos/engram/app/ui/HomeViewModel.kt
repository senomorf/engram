package photos.engram.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import photos.engram.app.AppContainer
import photos.engram.app.data.db.Counts

class HomeViewModel(
    container: AppContainer,
) : ViewModel() {
    val counts: StateFlow<Counts> =
        container.db
            .media()
            .counts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Counts(0, 0, 0, 0))
}
