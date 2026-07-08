package cam.engram.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// mauve brand scheme (seed ~#7D5260), used when dynamic color is turned off; the
// same identity carried by the launcher icon and the landing page
private val brandLight =
    lightColorScheme(
        primary = Color(0xFF8F4A5F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFD9E2),
        onPrimaryContainer = Color(0xFF3A0B22),
        secondary = Color(0xFF74565F),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFD9E2),
        onSecondaryContainer = Color(0xFF2B151C),
        tertiary = Color(0xFF7C5635),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFDCC1),
        onTertiaryContainer = Color(0xFF2E1500),
        background = Color(0xFFFFF8F8),
        onBackground = Color(0xFF22191C),
        surface = Color(0xFFFFF8F8),
        onSurface = Color(0xFF22191C),
        surfaceVariant = Color(0xFFF2DDE2),
        onSurfaceVariant = Color(0xFF514347),
        outline = Color(0xFF837377),
    )

private val brandDark =
    darkColorScheme(
        primary = Color(0xFFFFB1C7),
        onPrimary = Color(0xFF551D33),
        primaryContainer = Color(0xFF713349),
        onPrimaryContainer = Color(0xFFFFD9E2),
        secondary = Color(0xFFE2BDC6),
        onSecondary = Color(0xFF422931),
        secondaryContainer = Color(0xFF5A3F47),
        onSecondaryContainer = Color(0xFFFFD9E2),
        tertiary = Color(0xFFEFBD94),
        onTertiary = Color(0xFF48290B),
        tertiaryContainer = Color(0xFF613F20),
        onTertiaryContainer = Color(0xFFFFDCC1),
        background = Color(0xFF191113),
        onBackground = Color(0xFFEFDFE1),
        surface = Color(0xFF191113),
        onSurface = Color(0xFFEFDFE1),
        surfaceVariant = Color(0xFF514347),
        onSurfaceVariant = Color(0xFFD5C2C7),
        outline = Color(0xFF9E8C91),
    )

/**
 * Material You dynamic color by default; the mauve brand scheme when the user
 * turns dynamic color off in settings (design D21). At minSdk 33 dynamic color
 * is always available, so the branch is a user choice, not an SDK gate.
 */
@Composable
fun EngramTheme(
    dynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme =
        when {
            dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
            dynamicColor -> dynamicLightColorScheme(context)
            darkTheme -> brandDark
            else -> brandLight
        }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
