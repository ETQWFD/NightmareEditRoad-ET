package io.github.xororz.localdream.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.xororz.localdream.data.DarkModePreference
import io.github.xororz.localdream.data.ThemePreferences
import io.github.xororz.localdream.data.ThemeState

/**
 * Holds the live theme preferences plus a setter that also persists. Provided
 * via [LocalThemeController] so settings UIs can read and mutate without
 * threading state down through every screen.
 */
class ThemeController(initial: ThemeState, private val onPersist: (ThemeState) -> Unit) {
    var state: ThemeState by mutableStateOf(initial)
        private set

    fun update(transform: (ThemeState) -> ThemeState) {
        val next = transform(state)
        if (next != state) {
            state = next
            onPersist(next)
        }
    }
}

val LocalThemeController = compositionLocalOf<ThemeController> {
    error("ThemeController not provided")
}

@Composable
fun rememberThemeController(): ThemeController {
    val context = LocalContext.current
    val prefs = remember { ThemePreferences(context) }
    return remember { ThemeController(prefs.read(), prefs::write) }
}

private inline val ThemeState.systemFollowsDark: Boolean
    @Composable get() = when (darkMode) {
        DarkModePreference.SYSTEM -> isSystemInDarkTheme()
        DarkModePreference.LIGHT -> false
        DarkModePreference.DARK -> true
    }

/**
 * NERT 固定使用中性白 + 冷灰蓝色板：不读系统壁纸取色，也不再使用橙色/棕色。
 * 这样底部胶囊、设置面板才能呈现干净的白色半透明玻璃效果。
 */
private val NertLightColors = lightColorScheme(
    primary = Color(0xFF111827),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7EDFF),
    onPrimaryContainer = Color(0xFF172554),
    secondary = Color(0xFF475569),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9EEF7),
    onSecondaryContainer = Color(0xFF1E293B),
    tertiary = Color(0xFF2563EB),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = Color(0xFF5B6575),
    outline = Color(0xFFD5DDE8),
    outlineVariant = Color(0xFFE6EBF2),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFCFE),
    surfaceContainer = Color(0xFFF5F7FB),
    surfaceContainerHigh = Color(0xFFEFF3F9),
    surfaceContainerHighest = Color(0xFFE8EDF5),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NertTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = NertLightColors,
        typography = Typography,
        shapes = Shapes,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LocalDreamTheme(themeState: ThemeState, content: @Composable () -> Unit) {
    val darkTheme = themeState.systemFollowsDark
    val colorScheme = when {
        themeState.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        else -> themeState.preset.scheme(darkTheme)
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
