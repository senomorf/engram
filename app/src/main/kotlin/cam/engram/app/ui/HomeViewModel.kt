package cam.engram.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cam.engram.app.AppContainer
import cam.engram.app.data.db.Counts
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(
    container: AppContainer,
) : ViewModel() {
    val counts: StateFlow<Counts> =
        container.db
            .media()
            .counts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Counts(0, 0, 0, 0))
}
