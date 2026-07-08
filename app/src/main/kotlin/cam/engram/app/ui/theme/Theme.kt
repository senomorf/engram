package cam.engram.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val warmLight =
    lightColorScheme(
        primary = Color(0xFF7D5260),
        secondary = Color(0xFF625B71),
        tertiary = Color(0xFF7D7667),
    )

private val warmDark =
    darkColorScheme(
        primary = Color(0xFFD0BCC5),
        secondary = Color(0xFFCCC2DC),
        tertiary = Color(0xFFCFC8B8),
    )

@Composable
fun EngramTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> warmDark
            else -> warmLight
        }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
