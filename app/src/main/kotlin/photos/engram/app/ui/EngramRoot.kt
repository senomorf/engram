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
fun EngramRoot() {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val navigator = remember { Navigator(stack) }
    BackHandler(enabled = stack.size > 1) { navigator.pop() }
    when (stack.last()) {
        is Screen.Home -> HomeScreen(onOpenQueue = { navigator.push(Screen.Queue) })
        is Screen.Queue -> QueueScreen()
    }
}
