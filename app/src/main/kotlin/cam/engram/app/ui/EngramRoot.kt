package cam.engram.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Hand-rolled navigation: a screen stack of plain values. A handful of screens
 * do not justify a navigation library (and its alpha-channel churn); BackHandler
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

    data object Tools : Screen

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
    val container = currentAppContainer()
    val settings = remember { container.settings }
    val scope = rememberCoroutineScope()
    // observe the flow so finishing onboarding recomposes into the app at once;
    // a one-shot read would leave the user stuck until the next launch
    val current = settings.settings.collectAsState(initial = null).value
    when {
        current == null -> Unit // brief first settings read; nothing to draw yet
        !current.onboardingDone ->
            OnboardingScreen(onDone = {
                scope.launch { settings.setOnboardingDone(true) }
            })
        else -> MainNavigation(startInQueue)
    }
}

@Composable
private fun MainNavigation(startInQueue: Boolean) {
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
                onOpenTools = { navigator.push(Screen.Tools) },
                onOpenSettings = { navigator.push(Screen.Settings) },
                onOpenLab = { navigator.push(Screen.Lab) },
            )
        is Screen.Settings -> SettingsScreen(onBack = { navigator.pop() })
        is Screen.Browse ->
            BrowseScreen(
                onOpen = { navigator.push(Screen.Detail(it)) },
                onBack = { navigator.pop() },
            )
        is Screen.Detail ->
            MemoryDetailScreen(
                mediaId = screen.mediaId,
                onAnnotate = { navigator.push(Screen.Annotate(listOf(it), 0)) },
                onBack = { navigator.pop() },
            )
        is Screen.Tools -> ToolsScreen(onBack = { navigator.pop() })
        is Screen.Queue ->
            QueueScreen(
                onAnnotate = { ids, index -> navigator.push(Screen.Annotate(ids, index)) },
                onBack = { navigator.pop() },
            )
        is Screen.Annotate ->
            AnnotateScreen(
                mediaIds = screen.mediaIds,
                startIndex = screen.startIndex,
                onDone = { navigator.pop() },
            )
        is Screen.Lab -> LabScreen(onBack = { navigator.pop() })
    }
}
