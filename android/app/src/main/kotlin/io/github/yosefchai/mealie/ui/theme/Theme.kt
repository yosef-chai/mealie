package io.github.yosefchai.mealie.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.yosefchai.mealie.core.ThemeMode

// ערכת Material 3 בגוון הכתום של Mealie (#E58325), לפי תפקידי הצבע של M3.
internal val Light = lightColorScheme(
    primary = Color(0xFF8F4E00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF6D3A00),
    secondary = Color(0xFF745944),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCC5),
    onSecondaryContainer = Color(0xFF5A422E),
    tertiary = Color(0xFF5D6136),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE3E6AF),
    onTertiaryContainer = Color(0xFF454921),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF221A14),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF221A14),
    surfaceVariant = Color(0xFFF3DFD2),
    onSurfaceVariant = Color(0xFF52443B),
    outline = Color(0xFF84746A),
    outlineVariant = Color(0xFFD6C3B7),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF382E28),
    inverseOnSurface = Color(0xFFFFEDE4),
    inversePrimary = Color(0xFFFFB77C),
    surfaceDim = Color(0xFFE8D7CE),
    surfaceBright = Color(0xFFFFF8F5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1EA),
    surfaceContainer = Color(0xFFFCEBE2),
    surfaceContainerHigh = Color(0xFFF6E5DC),
    surfaceContainerHighest = Color(0xFFF1DFD6),
)

internal val Dark = darkColorScheme(
    primary = Color(0xFFFFB77C),
    onPrimary = Color(0xFF4D2700),
    primaryContainer = Color(0xFF6D3A00),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFE3BFA6),
    onSecondary = Color(0xFF422B19),
    secondaryContainer = Color(0xFF5A422E),
    onSecondaryContainer = Color(0xFFFFDCC5),
    tertiary = Color(0xFFC7CA95),
    onTertiary = Color(0xFF2F320C),
    tertiaryContainer = Color(0xFF454921),
    onTertiaryContainer = Color(0xFFE3E6AF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF19120C),
    onBackground = Color(0xFFF0DFD6),
    surface = Color(0xFF19120C),
    onSurface = Color(0xFFF0DFD6),
    surfaceVariant = Color(0xFF52443B),
    onSurfaceVariant = Color(0xFFD6C3B7),
    outline = Color(0xFF9E8D83),
    outlineVariant = Color(0xFF52443B),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFF0DFD6),
    inverseOnSurface = Color(0xFF382E28),
    inversePrimary = Color(0xFF8F4E00),
    surfaceDim = Color(0xFF19120C),
    surfaceBright = Color(0xFF413731),
    surfaceContainerLowest = Color(0xFF140D08),
    surfaceContainerLow = Color(0xFF221A14),
    surfaceContainer = Color(0xFF261E18),
    surfaceContainerHigh = Color(0xFF312822),
    surfaceContainerHighest = Color(0xFF3C332D),
)

/** צבע המותג המקורי, לאלמנטים שצריכים להיראות "Mealie" גם על רקע בהיר או כהה. */
val MealieOrange = Color(0xFFE58325)

@Composable
fun isAppDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MealieTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = isAppDark(mode)
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
