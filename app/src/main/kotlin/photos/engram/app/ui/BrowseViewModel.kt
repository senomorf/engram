package photos.engram.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import photos.engram.app.AppContainer
import photos.engram.app.data.db.MediaItemEntity

class BrowseViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val timeline: StateFlow<List<MediaItemEntity>> =
        container.db
            .media()
            .timeline()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val queryState = MutableStateFlow("")
    val query: StateFlow<String> = queryState

    private val resultsState = MutableStateFlow<List<MediaItemEntity>?>(null)
    val results: StateFlow<List<MediaItemEntity>?> = resultsState

    private var searchJob: Job? = null

    fun onQueryChange(value: String) {
        queryState.value = value
        searchJob?.cancel()
        if (value.isBlank()) {
            resultsState.value = null
            return
        }
        searchJob =
            viewModelScope.launch {
                delay(DEBOUNCE_MILLIS)
                resultsState.value =
                    runCatching { container.db.search().search(ftsQuery(value)) }.getOrDefault(emptyList())
            }
    }

    private fun ftsQuery(raw: String): String =
        raw
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { "${sanitize(it)}*" }

    // FTS4 treats these as syntax; strip so a user's text is a plain term
    private fun sanitize(term: String): String = term.replace(Regex("[\"*^:()-]"), "")

    private companion object {
        const val DEBOUNCE_MILLIS = 250L
    }
}
