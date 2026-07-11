package cam.engram.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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
        !current.onboardingDone -> {
            val context = LocalContext.current
            // API 33+ never grants notifications silently and the evening digest
            // defaults on, so ask once when onboarding completes (D30). The result is
            // deliberately unused: Notifier re-checks at post time and Settings
            // surfaces the denied state with a tap-through.
            val notifications =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            OnboardingScreen(onDone = {
                if (needsNotificationPermission(context)) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                scope.launch { settings.setOnboardingDone(true) }
            })
        }
        else -> MainNavigation(startInQueue)
    }
}

private fun needsNotificationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

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
