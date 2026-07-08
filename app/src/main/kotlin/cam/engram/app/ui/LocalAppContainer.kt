package cam.engram.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import cam.engram.app.AppContainer
import cam.engram.app.appContainer

/**
 * App-scoped DI seam for Compose. Production leaves it unset and falls back to the
 * real container on EngramApp; tests provide a fake via CompositionLocalProvider so
 * screens can be driven with an in-memory database and fake content access.
 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer?> { null }

@Composable
fun currentAppContainer(): AppContainer = LocalAppContainer.current ?: LocalContext.current.appContainer()
