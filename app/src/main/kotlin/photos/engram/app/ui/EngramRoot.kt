package photos.engram.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

/**
 * Hand-rolled navigation: a screen stack of plain values. Five screens do not
 * justify a navigation library (and its alpha-channel churn); BackHandler
 * plus a SnapshotStateList covers push, pop and replace.
 */
sealed interface Screen {
    data object Home : Screen

    data object Queue : Screen

    data class Annotate(
        val mediaIds: List<Long>,
        val startIndex: Int,
    ) : Screen

    data object Settings : Screen

    data object Browse : Screen

    data class Detail(
        val mediaId: Long,
    ) : Screen

    data object Lab : Screen
}

class Navigator internal constructor(
    private val stack: MutableList<Screen>,
) {
    val current: Screen get() = stack.last()

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

@Composable
fun EngramRoot(startInQueue: Boolean = false) {
    val stack =
        remember {
            mutableStateListOf<Screen>(Screen.Home).apply {
                if (startInQueue) add(Screen.Queue)
            }
        }
    val navigator = remember { Navigator(stack) }
    BackHandler(enabled = stack.size > 1) { navigator.pop() }
    when (val screen = stack.last()) {
        is Screen.Home ->
            HomeScreen(
                onOpenQueue = { navigator.push(Screen.Queue) },
                onOpenBrowse = { navigator.push(Screen.Browse) },
                onOpenSettings = { navigator.push(Screen.Settings) },
                onOpenLab = { navigator.push(Screen.Lab) },
            )
        is Screen.Settings -> SettingsScreen(onBack = { navigator.pop() })
        is Screen.Browse -> BrowseScreen(onOpen = { navigator.push(Screen.Detail(it)) })
        is Screen.Detail ->
            MemoryDetailScreen(
                mediaId = screen.mediaId,
                onAnnotate = { navigator.push(Screen.Annotate(listOf(it), 0)) },
            )
        is Screen.Queue ->
            QueueScreen(
                onAnnotate = { ids, index -> navigator.push(Screen.Annotate(ids, index)) },
            )
        is Screen.Annotate ->
            AnnotateScreen(
                mediaIds = screen.mediaIds,
                startIndex = screen.startIndex,
                onDone = { navigator.pop() },
            )
        is Screen.Lab -> LabScreen()
    }
}
